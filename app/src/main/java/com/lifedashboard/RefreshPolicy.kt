package com.lifedashboard

import org.json.JSONObject

private fun summaryInput(event: LifeEvent?): List<Any?>? {
    if (event == null || event.status != "ACTIVE" || event.type !in setOf("CALENDAR", "PAYMENT", "DELIVERY", "RESERVATION", "SLEEP", "EXERCISE", "STEP_SUMMARY")) return null
    val data = JSONObject(event.dataJson)
    val keys = when (event.type) {
        "PAYMENT" -> listOf("amount", "currency", "paymentKind")
        "SLEEP" -> listOf("sleepIntervals")
        "STEP_SUMMARY" -> listOf("count")
        "DELIVERY" -> listOf("deliveryStatus")
        else -> emptyList()
    }
    return listOf(event.type, event.occurredAt, event.endedAt, event.calendarDate, data.optString("date"), if (event.type in setOf("CALENDAR", "DELIVERY")) event.title else null) + keys.map { data.opt(it)?.toString() }
}
fun changesSummary(before: LifeEvent?, after: LifeEvent?) = summaryInput(before) != summaryInput(after)

class ResumeRefreshGate {
    private var completedAt: Long? = null
    private var completedDay: String? = null
    private var completedZone: String? = null
    fun shouldRefresh(now: Long, day: String, zone: String): Boolean {
        val previous = completedAt ?: return true
        return now < previous || now - previous >= 5 * 60_000 || completedDay != day || completedZone != zone
    }
    fun completed(now: Long, day: String, zone: String) { completedAt = now; completedDay = day; completedZone = zone }
    fun reset() { completedAt = null }
}
