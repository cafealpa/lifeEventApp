package com.lifedashboard

import android.content.Context
import android.health.connect.datatypes.Metadata as PlatformMetadata
import android.health.connect.datatypes.StepsRecord as PlatformSteps
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class HealthStepRecoveryTest {
    private val time = Instant.parse("2026-10-01T01:00:00Z")
    private fun native(end: Instant, id: String = "anonymous-step") = PlatformSteps.Builder(
        PlatformMetadata.Builder().setId(id).setLastModifiedTime(Instant.EPOCH)
            .setDataOrigin(android.health.connect.datatypes.DataOrigin.Builder().setPackageName("test.health.source").build()).build(), time, end, 42L)
        .setStartZoneOffset(ZoneOffset.UTC).setEndZoneOffset(ZoneOffset.UTC).build()

    @Test fun platformZeroDurationTriggersJetpackFailureButRawCanBePreserved() = runBlocking {
        val source = native(time)
        var retries = 0
        val record = recoverStepConversion({
            HealthStep.from(StepsRecord(source.startTime, source.startZoneOffset, source.endTime, source.endZoneOffset, source.count, Metadata.manualEntry()))
        }, { retries++; HealthStep.from(source) })
        assertEquals(1, retries)
        assertTrue(record.invalidTime)
        val (_, json) = record.payload()
        assertEquals("UNVERIFIED", json.getString("status"))
        assertEquals(time.toString(), json.getJSONObject("raw").getString("startTime"))
        assertEquals(time.toString(), json.getJSONObject("raw").getString("endTime"))
        assertEquals(42L, json.getJSONObject("raw").getLong("count"))
        assertEquals("anonymous-step", json.getJSONObject("raw").getJSONObject("metadata").getString("id"))
        assertFalse(json.has("durationMinutes"))
    }

    @Test fun normalStepsDoNotUseFallback() = runBlocking {
        val step = HealthStep.from(native(time.plusSeconds(60)))
        val result = recoverStepConversion({ step }, { error("Unexpected retry") })
        assertFalse(result.invalidTime)
        assertEquals(1L, result.payload().second.getLong("durationMinutes"))
    }

    @Test fun otherFailuresNeverTriggerNativeRetry() = runBlocking {
        val errors = listOf(IllegalArgumentException("startTime must be before endTime."),
            SecurityException("permission denied"), CancellationException("cancelled"))
        for (error in errors) {
            var retried = false
            try {
                recoverStepConversion<Unit>({ throw error }, { retried = true })
                fail("Expected original failure")
            } catch (caught: Exception) { assertSame(error, caught) }
            assertFalse(retried)
        }
    }

    @Test fun failedFallbackIsNotTurnedIntoEmptySuccessfulPage() = runBlocking {
        val denied = SecurityException("permission changed")
        try {
            recoverStepConversion<HealthStep>({
                HealthStep.from(StepsRecord(time, null, time, null, 42, Metadata.manualEntry()))
            }, { throw denied })
            fail("Expected fallback failure")
        } catch (e: SecurityException) { assertSame(denied, e) }
    }

    @Test fun mixedRecoveredPageKeepsAllRecordsAndCorrectionRestoresSameEvent() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LifeDatabase::class.java).build()
        try {
            val repository = LifeRepository(db)
            suspend fun save(step: HealthStep): LifeEvent {
                val (start, json) = step.payload()
                return repository.ingest("HEALTH_CONNECT", step.id, start, json)
            }
            val bad = HealthStep.from(native(time))
            val good = HealthStep.from(native(time.plusSeconds(60), "normal-step"))
            val first = save(bad); save(good); save(bad)
            assertEquals(2, db.dao().allEvents().size)
            assertEquals("UNVERIFIED", db.dao().event("HEALTH_CONNECT", bad.id)!!.status)
            assertEquals(1, db.dao().latestRaw("HEALTH_CONNECT", bad.id)!!.revision)
            val fixed = save(HealthStep.from(native(time.plusSeconds(60))))
            assertEquals(first.id, fixed.id); assertEquals("ACTIVE", fixed.status)
            assertEquals(2, db.dao().latestRaw("HEALTH_CONNECT", bad.id)!!.revision)
            assertEquals("ACTIVE", db.dao().event("HEALTH_CONNECT", good.id)!!.status)
            // Reprocessing retained raw revisions must keep the latest corrected state.
            repository.reprocess()
            assertEquals("ACTIVE", db.dao().event("HEALTH_CONNECT", bad.id)!!.status)
        } finally { db.close() }
    }

    @Test fun warningReportContainsCountButNoStepIdentityOrValues() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val diagnostics = HealthDiagnostics(context)
        diagnostics.clear()
        diagnostics.capture(false) { it.invalidSteps(1); it.at(HealthStage.SAVE_TOKEN) }
        val report = JSONObject(diagnostics.reports.value)
        assertEquals("SUCCESS_WITH_WARNINGS", report.getJSONObject("latestAttempt").getString("result"))
        assertEquals(1, report.getJSONObject("latestAttempt").getJSONObject("location").getInt("invalidStepRecords"))
        assertFalse(report.isNull("lastSuccessAt"))
        assertFalse(report.toString().contains("anonymous-step"))
        diagnostics.clear()
    }
}
