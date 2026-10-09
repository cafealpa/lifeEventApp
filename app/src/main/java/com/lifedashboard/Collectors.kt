package com.lifedashboard

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.*
import androidx.health.connect.client.time.TimeRangeFilter
import com.google.gson.GsonBuilder
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.*

class CalendarCollector(private val context: Context, private val repository: LifeRepository) {
    suspend fun collect() = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) throw SecurityException("일정 읽기 권한이 필요해요")
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).minusDays(30).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.now(zone).plusDays(91).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, start); ContentUris.appendId(it, end) }.build()
        val seen = mutableSetOf<String>()
        // A null cursor is an error, never evidence that all existing records disappeared.
        val cursor = context.contentResolver.query(uri, null, null, null, CalendarContract.Instances.BEGIN + " ASC") ?: error("캘린더 조회 실패")
        cursor.use {
            fun string(name: String) = it.getColumnIndex(name).takeIf { i -> i >= 0 && !it.isNull(i) }?.let(it::getString)
            fun long(name: String) = string(name)?.toLongOrNull() ?: 0L
            while (it.moveToNext()) {
                val begin = long(CalendarContract.Instances.BEGIN)
                val finish = long(CalendarContract.Instances.END)
                val id = long(CalendarContract.Instances.EVENT_ID)
                val recurring = !string(CalendarContract.Events.RRULE).isNullOrEmpty() || long(CalendarContract.Events.ORIGINAL_ID) != 0L
                val key = if (recurring) "$id:$begin" else "$id"
                seen.add(key)
                val raw = JSONObject()
                it.columnNames.forEachIndexed { i, name ->
                    if (!it.isNull(i)) raw.put(name, if (it.getType(i) == android.database.Cursor.FIELD_TYPE_BLOB) android.util.Base64.encodeToString(it.getBlob(i), android.util.Base64.NO_WRAP) else it.getString(i))
                }
                val allDay = long(CalendarContract.Events.ALL_DAY) == 1L
                val json = JSONObject().put("schemaVersion", 1).put("type", "CALENDAR").put("category", "SCHEDULE")
                    .put("title", string(CalendarContract.Events.TITLE) ?: "제목 없는 일정").put("summary", string(CalendarContract.Events.EVENT_LOCATION) ?: "")
                    .put("endedAt", finish).put("eventId", id).put("allDay", allDay).put("raw", raw)
                    .put("status", if (long(CalendarContract.Events.STATUS) == CalendarContract.Events.STATUS_CANCELED.toLong()) "CANCELLED" else "ACTIVE")
                if (allDay) json.put("date", Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate().toString())
                repository.ingest("CALENDAR", key, begin, json)
            }
        }
        // Reconcile only the successfully read bounded window.
        repository.dao.sourceWindow("CALENDAR", start, end).filter { it.sourceId !in seen }.forEach { repository.markDeleted(it) }
    }
}

class HealthCollector(private val context: Context, private val repository: LifeRepository) {
    val diagnostics = HealthDiagnostics(context)
    companion object {
        val permissions = setOf(HealthPermission.getReadPermission(StepsRecord::class), HealthPermission.getReadPermission(SleepSessionRecord::class), HealthPermission.getReadPermission(ExerciseSessionRecord::class))
    }
    fun availability() = HealthConnectClient.getSdkStatus(context)
    fun client() = HealthConnectClient.getOrCreate(context)
    fun backgroundSupported(): Boolean = availability() == HealthConnectClient.SDK_AVAILABLE && client().features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    suspend fun collect(background: Boolean) = diagnostics.capture(background) { trace -> collectInternal(background, trace) }
    private suspend fun collectInternal(background: Boolean, trace: HealthTrace) {
        check(availability() == HealthConnectClient.SDK_AVAILABLE) { "Health Connect 설치 또는 업데이트가 필요해요" }
        val client = client()
        trace.at(HealthStage.PERMISSIONS)
        val granted = client.permissionController.getGrantedPermissions()
        trace.permissions(HealthPermission.getReadPermission(StepsRecord::class) in granted,
            HealthPermission.getReadPermission(SleepSessionRecord::class) in granted,
            HealthPermission.getReadPermission(ExerciseSessionRecord::class) in granted,
            HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted)
        if (!granted.containsAll(permissions)) throw SecurityException("걸음·수면·운동 읽기 권한이 필요해요")
        trace.at(HealthStage.BACKGROUND)
        if (background && (!backgroundSupported() || HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in granted)) throw SecurityException("건강 데이터는 앱을 열면 갱신돼요 (백그라운드 권한 없음)")
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        // Always re-read a bounded 29-day window, including deletions and edits.
        val start = today.minusDays(29).atStartOfDay(zone).toInstant()
        val end = Instant.now()
        val seen = mutableSetOf<String>()
        val prefs = context.getSharedPreferences("health-sync", Context.MODE_PRIVATE)
        val recovery = HealthStepRecovery(context)
        val invalidSteps = mutableSetOf<String>()
        val changedStepDates = mutableSetOf<LocalDate>()
        fun trackSteps(event: LifeEvent?) {
            if (event?.type != "STEP") return
            var day = Instant.ofEpochMilli(event.occurredAt).atZone(zone).toLocalDate()
            val last = Instant.ofEpochMilli(maxOf(event.occurredAt, (event.endedAt ?: event.occurredAt) - 1)).atZone(zone).toLocalDate()
            while (!day.isAfter(last)) { changedStepDates.add(day); day = day.plusDays(1) }
        }
        var changesToken = prefs.getString("token", null)
        trace.at(HealthStage.TOKEN)
        if (changesToken == null) changesToken = client.getChangesToken(ChangesTokenRequest(setOf(StepsRecord::class, SleepSessionRecord::class, ExerciseSessionRecord::class)))
        suspend fun storeStep(record: HealthStep) {
            trace.processing(HealthStage.NORMALIZE, "STEP")
            val (begin, json) = record.payload()
            trace.processing(HealthStage.STORE, "STEP")
            trackSteps(repository.dao.event("HEALTH_CONNECT", record.id))
            trackSteps(repository.ingest("HEALTH_CONNECT", record.id, begin, json))
            seen.add(record.id)
            if (record.invalidTime) invalidSteps.add(record.id) else invalidSteps.remove(record.id)
            trace.invalidSteps(invalidSteps.size)
        }
        suspend fun store(record: Record) {
            if (record is StepsRecord) { storeStep(HealthStep.from(record)); return }
            val type = when (record) { is StepsRecord -> "STEP"; is SleepSessionRecord -> "SLEEP"; is ExerciseSessionRecord -> "EXERCISE"; else -> "ALL" }
            trace.processing(HealthStage.NORMALIZE, type)
            val key = record.metadata.id
            seen.add(key)
            val (begin, json) = HealthNormalizer.payload(record)
            trace.processing(HealthStage.STORE, type)
            if (record is StepsRecord) trackSteps(repository.dao.event("HEALTH_CONNECT", key))
            val stored = repository.ingest("HEALTH_CONNECT", key, begin, json)
            if (record is StepsRecord) trackSteps(stored)
        }
        var token: String? = null
        var pageNumber = 0
        do {
            trace.at(HealthStage.READ, "STEP", ++pageNumber)
            val page = recovery.read(client, start, end, token) { trace.processing(HealthStage.READ_RECOVERY, "STEP") }
            page.records.forEach { storeStep(it) }; token = page.next
        } while (token != null)
        pageNumber = 0
        do {
            trace.at(HealthStage.READ, "SLEEP", ++pageNumber)
            val page = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(start, end), pageToken = token))
            page.records.forEach { store(it) }; token = page.pageToken
        } while (token != null)
        pageNumber = 0
        do {
            trace.at(HealthStage.READ, "EXERCISE", ++pageNumber)
            val page = client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, TimeRangeFilter.between(start, end), pageToken = token))
            page.records.forEach { store(it) }; token = page.pageToken
        } while (token != null)
        trace.at(HealthStage.RECONCILE)
        repository.dao.sourceWindow("HEALTH_CONNECT", start.toEpochMilli(), end.toEpochMilli()).filter { it.sourceId !in seen }.forEach { repository.markDeleted(it) }
        var hasMore: Boolean
        pageNumber = 0
        do {
            trace.at(HealthStage.CHANGES, page = ++pageNumber)
            val changes = recovery.changes(client, requireNotNull(changesToken)) { trace.at(HealthStage.CHANGES_RECOVERY, page = pageNumber) }
            if (changes.expired) {
                // The bounded snapshot repaired recent history; older history is explicitly unverified.
                prefs.edit().remove("token").putBoolean("historyGap", true).commit()
                trace.at(HealthStage.TOKEN)
                changesToken = client.getChangesToken(ChangesTokenRequest(setOf(StepsRecord::class, SleepSessionRecord::class, ExerciseSessionRecord::class)))
                break
            }
            changes.steps.forEach { storeStep(it) }
            changes.records.forEach { store(it) }
            changes.deletions.forEach { recordId ->
                trace.at(HealthStage.APPLY_CHANGES, page = pageNumber)
                repository.dao.event("HEALTH_CONNECT", recordId)?.let { trackSteps(it); repository.markDeleted(it) }
                invalidSteps.remove(recordId)
                trace.invalidSteps(invalidSteps.size)
            }
            changesToken = changes.next
            hasMore = changes.more
        } while (hasMore)
        // A change outside the permitted read window must not leave a known-stale step total active.
        trace.at(HealthStage.OLD_TOTALS, "STEP_SUMMARY")
        for (day in changedStepDates.filter { it.isBefore(today.minusDays(29)) }) {
            repository.dao.event("DERIVED", "steps:$day")?.let { old ->
                repository.ingest("DERIVED", "steps:$day", old.occurredAt, JSONObject(old.dataJson)
                    .put("status", "UNVERIFIED").put("summary", "과거 기록 변경 감지 · 합계 재조회 범위 밖"))
            }
        }
        for (offset in 0L..29L) {
            trace.at(HealthStage.AGGREGATE, "STEP_SUMMARY", dayOffset = offset.toInt())
            val day = today.minusDays(offset)
            val dayStart = day.atStartOfDay(zone).toInstant()
            val dayEnd = minOf(day.plusDays(1).atStartOfDay(zone).toInstant(), end)
            val result = client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(dayStart, dayEnd)))
            val count = result[StepsRecord.COUNT_TOTAL]
            trace.at(HealthStage.STORE_TOTAL, "STEP_SUMMARY", dayOffset = offset.toInt())
            val json = JSONObject().put("schemaVersion", 1).put("type", "STEP_SUMMARY").put("category", "HEALTH").put("title", "하루 걸음수")
                .put("summary", count?.let { "${it}보" } ?: "기록 없음").put("count", count ?: 0L).put("date", day.toString()).put("zone", zone.id)
                .put("status", if (count == null) "NO_DATA" else "ACTIVE")
                .put("origins", JSONArray(result.dataOrigins.map { it.packageName }.sorted()))
                .put("method", "HealthConnect.aggregate(StepsRecord.COUNT_TOTAL)")
            repository.ingest("DERIVED", "steps:$day", dayStart.toEpochMilli(), json)
        }
        // Commit only after records, deletions and daily aggregates have all succeeded.
        trace.at(HealthStage.SAVE_TOKEN)
        prefs.edit().putString("token", changesToken).putInt("invalidSteps", invalidSteps.size).commit()
    }
}

class LifeNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val graph get() = (application as LifeApplication).graph
    private val activeKeys by lazy { getSharedPreferences("notification-active", Context.MODE_PRIVATE) }
    private fun guarded(block: () -> Unit) {
        try { block() }
        catch (e: Exception) { reportFailure(e) }
    }
    private fun reportFailure(e: Exception) {
        // Do not log exception messages or payloads: they can contain private notification text.
        android.util.Log.e("LifeNotification", "capture failure: ${e.javaClass.simpleName}")
        runCatching { graph.status.set("NOTIFICATION", "알림 처리 실패: ${e.javaClass.simpleName}") }
    }
    override fun onListenerConnected() = guarded {
        graph.status.set("NOTIFICATION", "연결됨 · 새 알림을 기다리는 중")
        activeNotifications?.let { notifications ->
            val keys = notifications.map { it.key }.toSet()
            activeKeys.edit().also { edit -> activeKeys.all.keys.filter { it !in keys }.forEach { edit.remove(it) } }.apply()
            notifications.forEach { capture(it) }
        }
    }
    override fun onListenerDisconnected() = guarded { graph.status.set("NOTIFICATION", "연결 끊김 · 시스템 재연결 대기") }
    override fun onNotificationPosted(sbn: StatusBarNotification) { capture(sbn) }
    override fun onNotificationRemoved(sbn: StatusBarNotification) = guarded { activeKeys.edit().remove(sbn.key).apply() }
    private fun capture(sbn: StatusBarNotification) = guarded {
        if (sbn.packageName != packageName && graph.status.enabled()) {
            // Preserve identity on the callback thread, before a remove/repost can change it.
            val firstPostTime = activeKeys.getLong(sbn.key, sbn.postTime)
            activeKeys.edit().putLong(sbn.key, firstPostTime).apply()
            pending.trySend(sbn to firstPostTime).also {
                if (it.isFailure) graph.status.set("NOTIFICATION", "수집 종료 중 · 알림을 저장하지 못했어요")
            }
        }
    }
    private val pending = kotlinx.coroutines.channels.Channel<Pair<StatusBarNotification, Long>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    override fun onCreate() {
        super.onCreate()
        scope.launch {
            // One consumer preserves update order and avoids one waiting coroutine per notification.
            for ((sbn, firstPostTime) in pending) {
                try { graph.acceptNotification("${sbn.key}:$firstPostTime", firstPostTime, NotificationPayload.create(sbn)) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { reportFailure(e) }
            }
        }
    }
    override fun onDestroy() { pending.close(); scope.cancel(); super.onDestroy() }
}

object HealthNormalizer {
    private val gson = GsonBuilder()
        .registerTypeAdapter(Instant::class.java, JsonSerializer<Instant> { src, _, _ -> JsonPrimitive(src.toString()) })
        .registerTypeAdapter(ZoneOffset::class.java, JsonSerializer<ZoneOffset> { src, _, _ -> JsonPrimitive(src.toString()) }).create()

    fun payload(record: Record): Pair<Long, JSONObject> {
            if (record is StepsRecord) return HealthStep.from(record).payload()
            val type: String; val begin: Instant; val finish: Instant; val title: String
            val detail = JSONObject(gson.toJson(record))
            when (record) {
                is StepsRecord -> { type = "STEP"; begin = record.startTime; finish = record.endTime; title = "걸음 기록"; detail.put("count", record.count) }
                is SleepSessionRecord -> { type = "SLEEP"; begin = record.startTime; finish = record.endTime; title = record.title ?: "수면" }
                is ExerciseSessionRecord -> { type = "EXERCISE"; begin = record.startTime; finish = record.endTime; title = record.title ?: "운동" }
                else -> error("Unsupported health record")
            }


            val json = JSONObject().put("schemaVersion", 1).put("type", type).put("category", "HEALTH").put("title", title)
                .put("endedAt", finish.toEpochMilli()).put("summary", if (record is StepsRecord) "${record.count}보" else "${Duration.between(begin, finish).toMinutes()}분")
                .put("durationMinutes", Duration.between(begin, finish).toMinutes()).put("raw", detail)
            if (record is StepsRecord) json.put("count", record.count)
            if (record is ExerciseSessionRecord) json.put("exerciseType", record.exerciseType)
            if (record is SleepSessionRecord) {
                val sleeping = setOf(SleepSessionRecord.STAGE_TYPE_SLEEPING, SleepSessionRecord.STAGE_TYPE_LIGHT, SleepSessionRecord.STAGE_TYPE_DEEP, SleepSessionRecord.STAGE_TYPE_REM)
                val intervals = if (record.stages.isEmpty()) listOf(begin to finish) else record.stages.filter { it.stage in sleeping }.map { it.startTime to it.endTime }
                val minutes = intervals.sumOf { Duration.between(it.first, it.second).toMillis() } / 60_000
                json.put("sleepIntervals", JSONArray(intervals.map { JSONArray(listOf(it.first.toEpochMilli(), it.second.toEpochMilli())) }))
                    .put("durationMinutes", minutes).put("summary", "${minutes}분${if (record.stages.isEmpty()) " (세션 길이)" else " (수면 단계 기준)"}")
                    .put("durationBasis", if (record.stages.isEmpty()) "SESSION_INTERVAL" else "SLEEP_STAGES")
            }
            return begin.toEpochMilli() to json
    }
}
