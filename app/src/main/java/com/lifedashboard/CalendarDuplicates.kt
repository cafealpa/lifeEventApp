package com.lifedashboard

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset

/** Keeps source rows intact; only the representative participates in views and summaries. */
object CalendarDuplicates {
    private fun clean(value: String) = value.trim().replace(Regex("[\\s\\p{Z}]+"), " ")
    private data class Key(val title: String, val start: String, val end: String, val allDay: Boolean)
    fun statuses(events: List<LifeEvent>): Map<String, String> {
        val eligible = events.filter { it.status in setOf("ACTIVE", "DUPLICATE") }
        val result = mutableMapOf<String, String>()
        eligible.groupBy { event ->
            val allDay = JSONObject(event.dataJson).optBoolean("allDay")
            fun date(time: Long) = Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC).toLocalDate().toString()
            Key(clean(event.title), if (allDay) event.calendarDate ?: date(event.occurredAt) else event.occurredAt.toString(),
                if (allDay) event.endedAt?.let(::date).orEmpty() else event.endedAt?.toString().orEmpty(), allDay)
        }.values.forEach { group ->
            // A missing place must not bridge two explicitly different places.
            val locations = group.groupBy { clean(it.summary.orEmpty()) }.toSortedMap()
            val known = locations.filterKeys { it.isNotEmpty() }.values.toList()
            val buckets = if (known.isEmpty()) listOf(group) else known.mapIndexed { index, rows ->
                rows + if (index == 0) locations[""].orEmpty() else emptyList()
            }
            buckets.forEach { rows ->
                val representative = rows.sortedWith(compareBy<LifeEvent> { clean(it.summary.orEmpty()).isEmpty() }
                    .thenBy { it.createdAt }.thenBy { it.sourceId }.thenBy { it.id }).first()
                rows.forEach { result[it.id] = if (it.id == representative.id) "ACTIVE" else "DUPLICATE" }
            }
        }
        return result
    }
}
