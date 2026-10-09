package com.lifedashboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class HealthDiagnosticsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun clear() { HealthDiagnostics(context).clear() }
    @Test fun failureRetainsExactStagePageAndRethrowsWithoutPrivateMessage() = runBlocking {
        val diagnostics = HealthDiagnostics(context)
        val failure = IllegalArgumentException("private health record title token-SECRET 12345", IllegalStateException("private cause"))
        try {
            diagnostics.capture(false) { trace ->
                trace.permissions(true, true, true, false)
                trace.at(HealthStage.READ, "SLEEP", 2)
                throw failure
            }
            fail("Expected failure")
        } catch (e: IllegalArgumentException) { assertSame(failure, e) }
        val text = HealthDiagnostics(context).reports.value
        assertFalse(text.contains("SECRET")); assertFalse(text.contains("private")); assertFalse(text.contains("12345"))
        val entry = JSONObject(text).getJSONObject("lastFailure")
        assertEquals("READ", entry.getJSONObject("location").getString("stage"))
        assertEquals(2, entry.getJSONObject("location").getInt("page"))
        assertFalse(entry.getJSONObject("location").getJSONObject("permissions").getBoolean("background"))
        assertTrue(diagnostics.failureSummary().startsWith("수면 · 원본 조회"))
    }
    @Test fun successfulRetryPreservesFailureAndClearRemovesBoth() = runBlocking {
        val d = HealthDiagnostics(context)
        try { d.capture(true) { it.at(HealthStage.AGGREGATE, "STEP_SUMMARY", dayOffset = 3); error("hidden") } } catch (_: IllegalStateException) { }
        val failure = JSONObject(d.reports.value).getJSONObject("lastFailure").toString()
        d.capture(false) { it.at(HealthStage.SAVE_TOKEN) }
        val report = JSONObject(HealthDiagnostics(context).reports.value)
        assertEquals("SUCCESS", report.getJSONObject("latestAttempt").getString("result"))
        assertEquals(failure, report.getJSONObject("lastFailure").toString())
        assertFalse(report.isNull("lastSuccessAt"))
        d.clear()
        val cleared = JSONObject(HealthDiagnostics(context).reports.value)
        assertTrue(cleared.isNull("latestAttempt")); assertTrue(cleared.isNull("lastFailure")); assertTrue(cleared.isNull("lastSuccessAt"))
    }
    @Test fun cancellationPropagatesWithoutBeingReportedAsFailure() = runBlocking {
        val d = HealthDiagnostics(context)
        val cancelled = CancellationException("private cancellation")
        try { d.capture(false) { throw cancelled }; fail("Expected cancellation") }
        catch (e: CancellationException) { assertSame(cancelled, e) }
        val report = JSONObject(d.reports.value)
        assertEquals("CANCELLED", report.getJSONObject("latestAttempt").getString("result"))
        assertTrue(report.isNull("lastFailure")); assertFalse(d.reports.value.contains("private cancellation"))
    }
    @Test fun errorExportUsesAllowlistAndBoundsCauseChain() {
        val error = IllegalArgumentException("startTime must be before endTime.")
        error.stackTrace = arrayOf(StackTraceElement("com.lifedashboard.HealthCollector", "collect", "private-file-name", 19),
            StackTraceElement("private.user.record", "secret", "secret", 22))
        val safe = HealthDiagnostics.safeError(error).toString()
        assertTrue(safe.contains("INVALID_TIME_RANGE")); assertTrue(safe.contains("HealthCollector.collect:19"))
        assertFalse(safe.contains("private")); assertFalse(safe.contains("secret"))
        val first = IllegalStateException("first"); val second = IllegalArgumentException("second")
        first.initCause(second); second.initCause(first)
        assertEquals(2, HealthDiagnostics.safeError(first).getJSONArray("causes").length())
    }
    @Test fun normalizationRetainsReadPage() {
        val trace = HealthTrace()
        trace.at(HealthStage.READ, "EXERCISE", 4)
        trace.processing(HealthStage.NORMALIZE, "EXERCISE")
        assertEquals(4, trace.json().getInt("page"))
        assertEquals("NORMALIZE", trace.json().getString("stage"))
    }
    @Test fun corruptOldReportDoesNotPreventCollection() = runBlocking {
        context.getSharedPreferences("health-diagnostics", Context.MODE_PRIVATE).edit().putString("latest", "bad json").commit()
        val d = HealthDiagnostics(context)
        d.capture(false) { }
        assertEquals("SUCCESS", JSONObject(d.reports.value).getJSONObject("latestAttempt").getString("result"))
    }
}
