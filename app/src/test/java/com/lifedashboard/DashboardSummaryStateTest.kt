package com.lifedashboard

import org.junit.Assert.*
import org.junit.Test

class DashboardSummaryStateTest {
    private fun row(date: String = "2026-10-08", amount: Long = 5000, stamp: Long = 10) =
        DailySummary(date,1000,420,30,1,amount,2,1,0,"{}",stamp)

    @Test fun dirtySnapshotKeepsLastValueUntilCompletedResultArrives() {
        val original = row()
        val first = DashboardSummaryState().update(listOf(original))
        val dirty = first.update(listOf(row(amount = 0,stamp = 0)))
        assertEquals(listOf(original),dirty.summaries)
        assertTrue(dirty.pending)
        val again = dirty.update(listOf(row(amount = 0,stamp = 0)))
        assertEquals(dirty,again)
        val completed = again.update(listOf(row(amount = 7000,stamp = 20)))
        assertEquals(7000L,completed.summaries.single().paymentAmount)
        assertFalse(completed.pending)
    }
    @Test fun missingHistoryIsNotInventedAndDatesNeverBorrowEachOthersValues() {
        val yesterday = row(date = "2026-10-07")
        val state = DashboardSummaryState().update(listOf(yesterday)).update(listOf(yesterday,row(stamp = 0)))
        assertEquals(listOf(yesterday),state.summaries)
        assertTrue(state.pending)
        assertTrue(DashboardSummaryState().update(listOf(row(stamp = 0))).summaries.isEmpty())
    }
    @Test fun deletingDataClearsRetainedValuesAndDoesNotResurrectThem() {
        val ready = DashboardSummaryState().update(listOf(row()))
        val cleared = ready.update(emptyList())
        assertTrue(cleared.summaries.isEmpty())
        assertFalse(cleared.pending)
        assertTrue(cleared.update(listOf(row(stamp = 0))).summaries.isEmpty())
    }
    @Test fun validZeroReplacesPreviousValueAndDeletedDatesDisappear() {
        val state = DashboardSummaryState().update(listOf(row(),row(date = "2026-10-07")))
            .update(listOf(row(amount = 0,stamp = 20)))
        assertEquals(1,state.summaries.size)
        assertEquals(0L,state.summaries.single().paymentAmount)
        assertFalse(state.pending)
    }
}
