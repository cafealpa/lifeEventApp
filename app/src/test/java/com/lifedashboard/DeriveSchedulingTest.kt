package com.lifedashboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DeriveSchedulingTest {
    private lateinit var graph: AppGraph
    private lateinit var manager: WorkManager
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        manager = WorkManager.getInstance(context)
        graph = AppGraph(context)
        graph.status.enable(true)
    }
    @After fun close() { manager.cancelAllWork().result.get(); graph.repository.db.close() }
    @Test fun burstHasOnePendingJobAndDuplicateDoesNotPostponeIt() = runBlocking<Unit> {
        val time = System.currentTimeMillis()
        repeat(10) { graph.acceptNotification("key", time, JSONObject().put("title", "알림").put("text", "갱신 $it")) }
        fun pending() = manager.getWorkInfosForUniqueWork("life-derive").get().filter { !it.state.isFinished }
        assertTrue(pending().isEmpty())
        repeat(10) { graph.acceptNotification("payment", time, JSONObject().put("title", "알림").put("text", "결제 완료 ${1000 + it}원")) }
        val job = pending().single()
        assertEquals(WorkInfo.State.ENQUEUED, job.state)
        graph.acceptNotification("key", time, JSONObject().put("title", "알림").put("text", "갱신 9"))
        assertEquals(job.id, pending().single().id)
        graph.acceptNotification("payment", time, JSONObject().put("title", "알림").put("text", "결제 완료 1009원"))
        assertEquals(job.id, pending().single().id)
        graph.derive()
        assertNull(graph.repository.dao.event("DERIVED", "briefing:${java.time.LocalDate.now()}"))
        // A final change after derivation must invalidate the cache and replace the pending request.
        graph.acceptNotification("payment", time, JSONObject().put("title", "알림").put("text", "현대카드 승인 5,900원"))
        assertNotEquals(job.id, pending().single().id)
        graph.derive()
        assertEquals(5900L, graph.repository.dao.summariesOnce().first { it.date == java.time.LocalDate.now().toString() }.paymentAmount)
    }
}
