package com.lifedashboard

import android.content.Context
import android.health.connect.HealthConnectManager
import android.health.connect.ReadRecordsRequestUsingFilters
import android.health.connect.ReadRecordsResponse
import android.health.connect.TimeInstantRangeFilter
import android.health.connect.changelog.ChangeLogsRequest
import android.health.connect.changelog.ChangeLogsResponse
import androidx.core.os.asOutcomeReceiver
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executor
import android.health.connect.datatypes.StepsRecord as PlatformSteps
import android.health.connect.datatypes.SleepSessionRecord as PlatformSleep
import android.health.connect.datatypes.ExerciseSessionRecord as PlatformExercise

/** An immutable copy of public step fields; it can also represent invalid source intervals. */
internal data class HealthStep(val id: String, val start: Instant, val end: Instant, val count: Long,
    val startOffset: ZoneOffset?, val endOffset: ZoneOffset?, val metadata: JSONObject) {
    val invalidTime get() = !start.isBefore(end)
    fun payload(): Pair<Long, JSONObject> {
        val raw = JSONObject().put("startTime", start.toString()).put("endTime", end.toString())
            .put("startZoneOffset", startOffset?.toString()).put("endZoneOffset", endOffset?.toString())
            .put("count", count).put("metadata", metadata)
        val json = JSONObject().put("schemaVersion", 1).put("type", "STEP").put("category", "HEALTH")
            .put("title", "걸음 기록").put("endedAt", end.toEpochMilli()).put("count", count).put("raw", raw)
            .put("summary", if (invalidTime) "시간 범위 확인 필요 · 원본 보존" else "${count}보")
        if (invalidTime) json.put("status", "UNVERIFIED").put("validationError", "INVALID_TIME_RANGE")
        else json.put("durationMinutes", Duration.between(start, end).toMinutes())
        return start.toEpochMilli() to json
    }
    companion object {
        private fun metadata(id: String, origin: String, modified: Instant, clientId: String?, version: Long,
            method: Int, manufacturer: String?, model: String?, deviceType: Int?) = JSONObject()
            .put("id", id).put("dataOrigin", JSONObject().put("packageName", origin))
            .put("lastModifiedTime", modified.toString()).put("clientRecordId", clientId).put("clientRecordVersion", version)
            .put("recordingMethod", method).put("device", deviceType?.let {
                JSONObject().put("type", it).put("manufacturer", manufacturer).put("model", model)
            })
        fun from(record: StepsRecord): HealthStep {
            val m = record.metadata; val d = m.device
            return HealthStep(m.id, record.startTime, record.endTime, record.count, record.startZoneOffset, record.endZoneOffset,
                metadata(m.id, m.dataOrigin.packageName, m.lastModifiedTime, m.clientRecordId, m.clientRecordVersion, m.recordingMethod, d?.manufacturer, d?.model, d?.type))
        }
        fun from(record: PlatformSteps): HealthStep {
            val m = record.metadata; val d = m.device
            return HealthStep(m.id, record.startTime, record.endTime, record.count, record.startZoneOffset, record.endZoneOffset,
                metadata(m.id, m.dataOrigin.packageName, m.lastModifiedTime, m.clientRecordId, m.clientRecordVersion, m.recordingMethod, d?.manufacturer, d?.model, d?.type))
        }
    }
}

internal data class HealthStepPage(val records: List<HealthStep>, val next: String?)
internal data class HealthChangePage(val records: List<Record>, val steps: List<HealthStep>, val deletions: List<String>, val next: String, val more: Boolean, val expired: Boolean)

/** Only this confirmed Jetpack constructor failure permits a platform retry. Other errors propagate. */
internal suspend fun <T> recoverStepConversion(primary: suspend () -> T, fallback: suspend () -> T): T = try { primary() }
catch (e: IllegalArgumentException) {
    if (e.message != "startTime must be before endTime." || e.stackTrace.none {
            it.className == "androidx.health.connect.client.records.StepsRecord" && it.methodName == "<init>"
        }) throw e
    fallback()
}

internal class HealthStepRecovery(context: Context) {
    private val manager by lazy { requireNotNull(context.getSystemService(HealthConnectManager::class.java)) }
    private val executor = Executor { it.run() }
    suspend fun read(client: HealthConnectClient, start: Instant, end: Instant, token: String?, onRecovery: () -> Unit): HealthStepPage =
        recoverStepConversion({
            val page = client.readRecords(ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(start, end), pageToken = token))
            HealthStepPage(page.records.map(HealthStep::from), page.pageToken?.takeIf { it.isNotEmpty() })
        }, {
            onRecovery()
            val request = ReadRecordsRequestUsingFilters.Builder(PlatformSteps::class.java)
                .setTimeRangeFilter(TimeInstantRangeFilter.Builder().setStartTime(start).setEndTime(end).build())
                .setPageSize(1000).apply { if (token == null) setAscending(true) else setPageToken(token.toLong()) }.build()
            val page = suspendCancellableCoroutine<ReadRecordsResponse<PlatformSteps>> { continuation ->
                manager.readRecords(request, executor, continuation.asOutcomeReceiver())
            }
            HealthStepPage(page.records.map(HealthStep::from), page.nextPageToken.takeUnless { it == -1L }?.toString())
        })

    suspend fun changes(client: HealthConnectClient, token: String, onRecovery: () -> Unit): HealthChangePage =
        recoverStepConversion({
            val page = client.getChanges(token)
            val records = mutableListOf<Record>()
            val deletions = mutableListOf<String>()
            for (change in page.changes) when (change) {
                is UpsertionChange -> records += change.record
                is DeletionChange -> deletions += change.recordId
                else -> error("지원하지 않는 건강 변경 형식")
            }
            HealthChangePage(records, emptyList(), deletions, page.nextChangesToken, page.hasMore, page.changesTokenExpired)
        }, {
            onRecovery()
            val page = suspendCancellableCoroutine<ChangeLogsResponse> { continuation ->
                manager.getChangeLogs(ChangeLogsRequest.Builder(token).build(), executor, continuation.asOutcomeReceiver())
            }
            val steps = mutableListOf<HealthStep>()
            val records = mutableListOf<Record>()
            for (record in page.upsertedRecords) when (record) {
                is PlatformSteps -> steps += HealthStep.from(record)
                // Re-read the other requested types through Jetpack; never silently discard a change.
                is PlatformSleep -> records += client.readRecord(SleepSessionRecord::class, record.metadata.id).record
                is PlatformExercise -> records += client.readRecord(ExerciseSessionRecord::class, record.metadata.id).record
                else -> error("지원하지 않는 건강 변경 자료형")
            }
            HealthChangePage(records, steps, page.deletedLogs.map { it.deletedRecordId }, page.nextChangesToken, page.hasMorePages(), false)
        })
}
