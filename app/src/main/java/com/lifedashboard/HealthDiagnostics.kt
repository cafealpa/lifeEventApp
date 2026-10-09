package com.lifedashboard

import android.content.Context
import android.os.Build
import android.os.ext.SdkExtensions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

enum class HealthStage(val label: String) {
    AVAILABILITY("지원 상태 확인"), PERMISSIONS("권한 확인"), BACKGROUND("백그라운드 지원 확인"),
    TOKEN("변경 토큰 발급"), READ("원본 조회·SDK 변환"), NORMALIZE("원본 정규화"),
    STORE("원본 저장"), RECONCILE("삭제 대조"), CHANGES("변경 이력 조회·SDK 변환"),
    READ_RECOVERY("걸음 원본 호환 조회"), CHANGES_RECOVERY("변경 이력 호환 조회"),
    APPLY_CHANGES("변경 이력 반영"), OLD_TOTALS("과거 합계 상태 갱신"),
    AGGREGATE("걸음 합계 조회"), STORE_TOTAL("걸음 합계 저장"), SAVE_TOKEN("변경 토큰 저장")
}

class HealthTrace internal constructor() {
    var stage = HealthStage.AVAILABILITY; private set
    private var type = "ALL"
    private var page = 0
    private var dayOffset = -1
    private var permissions: JSONObject? = null
    var invalidStepCount = 0; private set
    fun invalidSteps(count: Int) { invalidStepCount = count }
    fun at(stage: HealthStage, type: String = "ALL", page: Int = 0, dayOffset: Int = -1) {
        require(type in setOf("ALL", "STEP", "SLEEP", "EXERCISE", "STEP_SUMMARY"))
        this.stage = stage; this.type = type; this.page = page; this.dayOffset = dayOffset
    }
    fun permissions(steps: Boolean, sleep: Boolean, exercise: Boolean, background: Boolean) {
        permissions = JSONObject().put("steps", steps).put("sleep", sleep).put("exercise", exercise).put("background", background)
    }
    fun processing(stage: HealthStage, type: String) = at(stage, type, page, dayOffset)
    internal fun json() = JSONObject().put("stage", stage.name).put("stageLabel", stage.label)
        .put("recordType", type).put("page", page).put("aggregateDaysAgo", dayOffset)
        .put("permissions", permissions ?: JSONObject.NULL).put("invalidStepRecords", invalidStepCount)
}

/** Allowlisted metadata only: never serialize records, token values or exception messages. */
class HealthDiagnostics(private val context: Context) {
    private val prefs = context.getSharedPreferences("health-diagnostics", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(report())
    val reports = state.asStateFlow()
    private fun environment() = JSONObject().put("appVersion", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE)
        .put("androidApi", Build.VERSION.SDK_INT).put("sdkExtension34", SdkExtensions.getExtensionVersion(Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
        .put("connectClient", "1.1.0").put("healthProviderVersion", providerVersion())
    private fun providerVersion(): String {
        for (name in listOf("com.google.android.healthconnect.controller", "com.android.healthconnect.controller", "com.google.android.apps.healthdata")) {
            try { return "$name:${context.packageManager.getPackageInfo(name, 0).longVersionCode}" }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) { }
        }
        return "확인 불가"
    }
    suspend fun capture(background: Boolean, block: suspend (HealthTrace) -> Unit) {
        val trace = HealthTrace()
        val entry = JSONObject().put("schemaVersion", 1).put("startedAt", Instant.now().toString())
            .put("mode", if (background) "BACKGROUND" else "FOREGROUND").put("lookbackDays", 29)
            .put("environment", runCatching { environment() }.getOrElse { JSONObject().put("status", "UNAVAILABLE") })
        save(JSONObject(entry.toString()).put("result", "RUNNING"))
        try {
            block(trace)
            entry.put("result", if (trace.invalidStepCount > 0) "SUCCESS_WITH_WARNINGS" else "SUCCESS")
        } catch (e: CancellationException) {
            entry.put("result", "CANCELLED")
            throw e
        } catch (e: Exception) {
            entry.put("result", "FAILED").put("error", safeError(e))
            throw e
        } finally {
            entry.put("finishedAt", Instant.now().toString()).put("location", trace.json())
            save(entry)
        }
    }
    private fun save(entry: JSONObject) {
        prefs.edit().putString("latest", entry.toString()).apply {
            if (entry.optString("result") == "FAILED") putString("lastFailure", entry.toString())
            if (entry.optString("result") in setOf("SUCCESS", "SUCCESS_WITH_WARNINGS")) putString("lastSuccessAt", entry.getString("finishedAt"))
        }.apply()
        state.value = report()
    }
    fun clear() { prefs.edit().clear().apply(); state.value = report() }
    fun failureSummary(): String {
        val location = stored("lastFailure")?.optJSONObject("location") ?: return "진단 결과를 확인해 주세요"
        val stage = runCatching { HealthStage.valueOf(location.getString("stage")).label }.getOrDefault("알 수 없는 단계")
        val type = when (location.optString("recordType")) { "STEP" -> "걸음"; "SLEEP" -> "수면"; "EXERCISE" -> "운동"; "STEP_SUMMARY" -> "걸음 합계"; else -> "건강" }
        return "$type · $stage 실패 · 건강 연결 진단에서 상세 확인"
    }
    private fun stored(key: String): JSONObject? = runCatching { prefs.getString(key, null)?.let(::JSONObject) }.getOrNull()
    private fun report(): String = JSONObject().put("diagnostic", "Life Dashboard Health Connect")
        .put("latestAttempt", stored("latest") ?: JSONObject.NULL)
        .put("lastFailure", stored("lastFailure") ?: JSONObject.NULL)
        .put("lastSuccessAt", prefs.getString("lastSuccessAt", null) ?: JSONObject.NULL).toString(2)
    companion object {
        internal fun safeError(error: Throwable): JSONObject {
            val causes = JSONArray()
            val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            var cause: Throwable? = error
            while (cause != null && causes.length() < 4 && visited.add(cause)) {
                val current = cause
                val kind = when (current) {
                    is SecurityException -> "SecurityException"
                    is IllegalArgumentException -> "IllegalArgumentException"
                    is IllegalStateException -> "IllegalStateException"
                    is java.io.IOException -> "IOException"
                    is android.os.RemoteException -> "RemoteException"
                    is android.health.connect.HealthConnectException -> "HealthConnectException"
                    else -> "OtherException"
                }
                val frames = current.stackTrace.filter { frame ->
                    listOf("com.lifedashboard.", "androidx.health.connect.", "android.health.connect.", "com.google.gson.").any { frame.className.startsWith(it) }
                }.take(8).map { frame ->
                    // No exception message, source file name or caller-supplied data.
                    "${frame.className.replace(Regex("[^A-Za-z0-9_.$]"), "?").take(180)}.${frame.methodName.replace(Regex("[^A-Za-z0-9_$<>-]"), "?").take(100)}:${frame.lineNumber}"
                }
                val item = JSONObject().put("kind", kind).put("frames", JSONArray(frames))
                if (current is android.health.connect.HealthConnectException) item.put("platformCode", current.errorCode)
                val reason = when (current.message) {
                    "startTime must be before endTime.", "end time needs be after start time" -> "INVALID_TIME_RANGE"
                    "segments can not overlap.", "laps can not overlap." -> "OVERLAPPING_EXERCISE_INTERVALS"
                    "segments can not be out of parent time range.", "laps can not be out of parent time range.", "route can not be out of parent time range." -> "EXERCISE_OUTSIDE_SESSION"
                    "segmentType and sessionType is not compatible." -> "INCOMPATIBLE_EXERCISE_TYPE"
                    else -> "UNCLASSIFIED"
                }
                causes.put(item.put("reason", reason)); cause = current.cause
            }
            return JSONObject().put("causes", causes)
        }
    }
}
