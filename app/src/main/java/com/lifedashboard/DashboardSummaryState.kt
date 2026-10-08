package com.lifedashboard

/** Keeps only previously displayed valid values while the same dates are dirty. */
data class DashboardSummaryState(
    val summaries: List<DailySummary> = emptyList(),
    val pending: Boolean = false
) {
    fun update(rows: List<DailySummary>): DashboardSummaryState {
        val previous = summaries.associateBy { it.date }
        return DashboardSummaryState(
            summaries = rows.mapNotNull { row -> if (row.updatedAt > 0) row else previous[row.date] }.sortedByDescending { it.date },
            pending = rows.any { it.updatedAt == 0L }
        )
    }
}
