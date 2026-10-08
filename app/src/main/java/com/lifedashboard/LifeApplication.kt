package com.lifedashboard

import android.app.Application
import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

class CollectionStatus(context: Context) {
    private val prefs = context.getSharedPreferences("collection", Context.MODE_PRIVATE)
    val states = MutableStateFlow(read())
    private fun read() = listOf("CALENDAR", "HEALTH_CONNECT", "NOTIFICATION", "PROCESSING").associateWith {
        prefs.getString(it, "미수집")!! + prefs.getLong("${it}_last", 0).takeIf { time -> time > 0 }?.let { time -> "\n마지막 성공: ${java.time.Instant.ofEpochMilli(time).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()}" }.orEmpty()
    }
    fun set(source: String, state: String) { prefs.edit().putString(source, state).apply(); states.value = read() }
    fun success(source: String) { prefs.edit().putString(source, "수집 완료").putLong("${source}_last", System.currentTimeMillis()).apply(); states.value = read() }
    fun enabled() = prefs.getBoolean("enabled", false)
    fun enable(value: Boolean) { prefs.edit().putBoolean("enabled", value).commit() }
    fun clear() { prefs.edit().clear().commit(); states.value = read() }
}

class AppGraph(val context: Context) {
    val repository = LifeRepository(LifeDatabase.create(context))
    val status = CollectionStatus(context)
    private val aggregationState = MutableStateFlow(false)
    val aggregating = aggregationState.asStateFlow()
    val calendar = CalendarCollector(context, repository)
    val health = HealthCollector(context, repository)
    private val syncMutex = Mutex()
    private val notificationMutex = Mutex()
    suspend fun refresh(background: Boolean = false) = syncMutex.withLock {
        if (!status.enabled()) return@withLock
        if (context.packageName !in androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context)) status.set("NOTIFICATION", "알림 접근 권한이 필요해요")
        suspend fun run(source: String, block: suspend () -> Unit) {
            status.set(source, "수집 중")
            try { block(); status.success(source) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { status.set(source, if (e is SecurityException || e is IllegalStateException) e.message ?: "권한 또는 지원 상태 확인 필요" else "수집 실패: ${e.javaClass.simpleName}") }
        }
        val recoveryFailures = repository.recoverPending()
        run("CALENDAR") { calendar.collect() }
        run("HEALTH_CONNECT") { health.collect(background) }
        if (status.states.value.getValue("HEALTH_CONNECT").startsWith("수집 완료") && context.getSharedPreferences("health-sync", Context.MODE_PRIVATE).getBoolean("historyGap", false)) {
            status.set("HEALTH_CONNECT", "최근 29일 갱신 · 변경 토큰 만료로 이전 기록의 변경 여부는 미확인")
        }
        deriveInternal()
        if (recoveryFailures > 0) status.set("PROCESSING", "집계 완료 · 분석 실패 원본 ${recoveryFailures}건 보존 중")
    }
    suspend fun derive() = syncMutex.withLock { if (status.enabled()) deriveInternal() }
    private suspend fun deriveInternal() {
        aggregationState.value = true
        try {
            repository.rebuildSummaries()
            repository.generateBriefing()
            status.success("PROCESSING")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { status.set("PROCESSING", "집계 실패: ${e.javaClass.simpleName}"); throw e }
        finally { aggregationState.value = false }
    }
    suspend fun reprocess() = syncMutex.withLock { repository.reprocess(); deriveInternal() }
    suspend fun classifyNotification(id: String, type: String?, amount: Long?, cancelled: Boolean): LifeEvent = syncMutex.withLock {
        val event = repository.classifyNotification(id,type,amount,cancelled)
        deriveInternal()
        event
    }
    suspend fun acceptNotification(key: String, time: Long, payload: org.json.JSONObject) = notificationMutex.withLock {
        if (status.enabled()) {
            val before = repository.dao.event("NOTIFICATION", key)
            val after = repository.ingest("NOTIFICATION", key, time, payload)
            if (payload.has("captureErrors")) status.set("NOTIFICATION", "일부 알림 필드를 읽지 못했어요 · 읽은 원본은 보존했어요")
            else status.success("NOTIFICATION")
            // A replacement also covers a notification arriving as the previous worker finishes.
            if (before != after) scheduleRefresh()
        }
    }
    suspend fun clear() = syncMutex.withLock { notificationMutex.withLock {
        status.enable(false)
        repository.clear()
        context.getSharedPreferences("health-sync", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("notification-active", Context.MODE_PRIVATE).edit().clear().commit()
        status.clear()
    } }
    fun schedule() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("life-sync", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).build())
        val now = java.time.ZonedDateTime.now()
        val nextMorning = now.toLocalDate().atTime(7, 0).atZone(now.zone).let { if (it.isAfter(now)) it else it.plusDays(1) }
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("life-morning", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(java.time.Duration.between(now, nextMorning).toMillis(), TimeUnit.MILLISECONDS).build())
    }
    suspend fun scheduleRefresh() {
        WorkManager.getInstance(context).enqueueUniqueWork("life-derive", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DeriveWorker>().setInitialDelay(5, TimeUnit.SECONDS).build()).await()
    }
}

class LifeApplication : Application() {
    val graph by lazy { AppGraph(this) }
    override fun onCreate() { super.onCreate(); if (graph.status.enabled()) graph.schedule() }
}
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        (applicationContext as LifeApplication).graph.refresh(background = true); Result.success()
    } catch (e: CancellationException) { throw e } catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
}
class DeriveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val graph = (applicationContext as LifeApplication).graph
        if (graph.status.enabled()) graph.derive()
        Result.success()
    } catch (e: CancellationException) { throw e } catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
}
