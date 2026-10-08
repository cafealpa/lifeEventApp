package com.lifedashboard

import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.security.MessageDigest
import java.time.*
import java.util.UUID

class LifeRepository(val db: LifeDatabase) {
    val dao = db.dao()
    private val mutex = Mutex()
    private val parser = NotificationParser()

    suspend fun ingest(source: String, key: String, time: Long, payload: JSONObject): LifeEvent = mutex.withLock {
        val json = JSONObject(payload.toString()).put("occurredAt", time).toString()
        val hash = MessageDigest.getInstance("SHA-256").digest(json.toByteArray()).joinToString("") { "%02x".format(it) }
        val previous = dao.latestRaw(source, key)
        val raw = if (previous?.hash == hash) previous else RawEvent(UUID.randomUUID().toString(), source, key,
            (previous?.revision ?: 0) + 1, time, System.currentTimeMillis(), json, hash).also { dao.insertRaw(it) }
        val existing = dao.event(source, key)
        if (existing?.rawEventId == raw.id && (source != "NOTIFICATION" || JSONObject(existing.dataJson).optInt("parserVersion") == NotificationParser.VERSION)) existing else normalize(raw)
    }

    private suspend fun normalize(raw: RawEvent): LifeEvent {
        val json = JSONObject(raw.rawJson)
        val normalizedData = JSONObject(raw.rawJson).also { it.remove("raw") }
        val automatic = if (raw.sourceType == "NOTIFICATION") parser.parse(raw).single() else ParsedEvent(
            json.getString("type"), json.optString("category", "HEALTH"), json.getString("title"), json.optString("summary"),
            normalizedData, json.optString("status", "ACTIVE"))
        val old = dao.event(raw.sourceType, raw.sourceKey)
        val parsed = if (raw.sourceType == "NOTIFICATION") applyManualClassification(automatic, json, old?.let { JSONObject(it.dataJson).optJSONObject("manualClassification") }) else automatic
        val now = System.currentTimeMillis()
        val event = LifeEvent(old?.id ?: UUID.randomUUID().toString(), parsed.type, parsed.category, raw.occurredAt,
            if (json.has("endedAt")) json.getLong("endedAt") else null, parsed.title, parsed.summary,
            raw.sourceType, raw.sourceKey, raw.id, parsed.data.toString(), if (parsed.type in setOf("NOTIFICATION", "ADVERTISEMENT")) 0.2 else 0.5,
            old?.createdAt ?: now, now, parsed.status, if (parsed.type == "CALENDAR" && json.optBoolean("allDay")) json.optString("date") else null)
        db.withTransaction {
            dao.put(event)
            dao.clearTags(event.id); dao.clearEntities(event.id)
            dao.tags(parsed.tags.map { EventTag(event.id, it) })
            dao.entities(parsed.entities.map { (kind, name) -> EventEntity("${event.id}:$kind:$name", event.id, kind, name, name.lowercase().replace(" ", "")) })
            // Persist dirty dates in the existing cache table; a crash cannot lose invalidation.
            val zone = ZoneId.systemDefault()
            (affectedDates(event, zone) + old?.let { affectedDates(it, zone) }.orEmpty()).forEach { date ->
                dao.putSummary(DailySummary(date.toString(), null, null, null, 0, 0, 0, 0, 0, "{}", 0))
            }
        }
        return event
    }

    suspend fun classifyNotification(id: String, type: String?, amount: Long? = null, cancelled: Boolean = false): LifeEvent = mutex.withLock {
        db.withTransaction {
            val event = requireNotNull(dao.eventById(id)) { "알림을 찾을 수 없어요" }
            require(event.sourceType == "NOTIFICATION") { "알림만 분류를 변경할 수 있어요" }
            val raw = requireNotNull(dao.latestRaw(event.sourceType, requireNotNull(event.sourceId)))
            val data = JSONObject(event.dataJson)
            if (type == null) data.remove("manualClassification") else {
                require(type in notificationClassifications) { "지원하지 않는 분류예요" }
                val choice = JSONObject().put("type",type).put("changedAt",System.currentTimeMillis())
                if (type == "PAYMENT") {
                    require(amount != null && amount > 0) { "결제 금액을 확인해 주세요" }
                    choice.put("amount",amount).put("paymentKind",if (cancelled) "CANCELLATION" else "APPROVAL")
                }
                data.put("manualClassification",choice)
            }
            dao.put(event.copy(dataJson = data.toString()))
            normalize(raw)
        }
    }

    suspend fun reprocess() = mutex.withLock { dao.latestRaws().forEach { normalize(it) } }

    suspend fun recoverPending(): Int = mutex.withLock {
        var failures = 0
        dao.latestRaws().forEach { raw ->
            val event = dao.event(raw.sourceType, raw.sourceKey)
            try {
                if (event?.rawEventId != raw.id || (raw.sourceType == "NOTIFICATION" && JSONObject(event.dataJson).optInt("parserVersion") != NotificationParser.VERSION)) normalize(raw)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: org.json.JSONException) { failures++ }
        }
        failures
    }

    suspend fun markDeleted(event: LifeEvent) {
        val json = JSONObject(event.dataJson).put("type", event.type).put("category", event.category)
            .put("title", event.title).put("summary", event.summary).put("status", "DELETED")
        event.endedAt?.let { json.put("endedAt", it) }
        ingest(event.sourceType, requireNotNull(event.sourceId), event.occurredAt, json)
    }

    suspend fun rebuildSummaries(zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone), force: Boolean = false) = mutex.withLock {
        val existing = dao.summaryRows().associateBy { LocalDate.parse(it.date) }
        val timezoneChanged = existing.values.any { it.updatedAt > 0 && JSONObject(it.summaryJson).optString("zone") != zone.id }
        val dates = mutableSetOf<LocalDate>()
        dates.add(today.plusDays(1))
        (0L..8L).forEach { dates.add(today.minusDays(it)) }
        // Normal writes persist every affected date as dirty in the same transaction.
        // A timezone change or explicit repair also discovers dates from all existing events.
        if (force || timezoneChanged) dao.allEvents().forEach { dates.addAll(affectedDates(it, zone)) }
        dates.addAll(existing.keys)
        val recalculate = dates.filter { force || timezoneChanged || existing[it] == null || existing[it]?.updatedAt == 0L }
        db.withTransaction {
            recalculate.sorted().forEach { date ->
                val events = dao.summaryEvents(date.atStartOfDay(zone).toInstant().toEpochMilli(),
                    date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), date.toString())
                dao.putSummary(SummaryCalculator.calculate(date, events, zone))
            }
        }
    }

    suspend fun generateBriefing(today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()) = mutex.withLock {
        val summaries = dao.summariesOnce()
        val yesterday = summaries.find { it.date == today.minusDays(1).toString() }
        val current = summaries.find { it.date == today.toString() }
        val history = summaries.filter { it.date >= today.minusDays(8).toString() && it.date < today.minusDays(1).toString() }.mapNotNull { it.sleepMinutes }
        val morning = dao.calendarForDay(today.atStartOfDay(zone).toInstant().toEpochMilli(), today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), today.toString()).count { it.type == "CALENDAR" && it.status == "ACTIVE" && eventDate(it, zone) == today && Instant.ofEpochMilli(it.occurredAt).atZone(zone).hour < 12 }
        val body = BriefingBuilder.build(yesterday, current, history, morning)
        val key = "briefing:$today"
        val old = dao.event("DERIVED", key)
        val now = System.currentTimeMillis()
        val data = JSONObject().put("schemaVersion", 1).put("generatorVersion", 1).put("date", today.toString())
            .put("zone", zone.id).put("inputFrom", today.minusDays(8).toString()).put("inputTo", today.toString()).put("generatedAt", now)
            .put("inputSummaryUpdatedAt", org.json.JSONArray(summaries.filter { it.date >= today.minusDays(8).toString() && it.date <= today.toString() }.map { JSONObject().put("date", it.date).put("updatedAt", it.updatedAt) }))
        dao.put(LifeEvent(old?.id ?: UUID.randomUUID().toString(), "BRIEFING", "LIFE", today.atStartOfDay(zone).toInstant().toEpochMilli(), null,
            "$today 아침 브리핑", body, "DERIVED", key, null, data.toString(), 0.5, old?.createdAt ?: now, now))
    }

    suspend fun clear() = mutex.withLock { db.withTransaction { dao.clearEvents(); dao.clearRaws(); dao.clearSummaries() } }
}

fun affectedDates(event: LifeEvent, zone: ZoneId): Set<LocalDate> {
    if (event.type == "BRIEFING") return emptySet()
    val dates = mutableSetOf(eventDate(event, zone))
    if (event.type == "EXERCISE") {
        var date = Instant.ofEpochMilli(event.occurredAt).atZone(zone).toLocalDate()
        val end = Instant.ofEpochMilli(maxOf(event.occurredAt, (event.endedAt ?: event.occurredAt) - 1)).atZone(zone).toLocalDate()
        while (!date.isAfter(end)) { dates.add(date); date = date.plusDays(1) }
    }
    return dates
}

fun eventDate(event: LifeEvent, zone: ZoneId): LocalDate {
    val data = JSONObject(event.dataJson)
    if (data.has("date")) return LocalDate.parse(data.getString("date"))
    return Instant.ofEpochMilli(if (event.type == "SLEEP") event.endedAt ?: event.occurredAt else event.occurredAt).atZone(zone).toLocalDate()
}

object SummaryCalculator {
    fun calculate(date: LocalDate, events: List<LifeEvent>, zone: ZoneId): DailySummary {
        val active = events.filter { it.status == "ACTIVE" }
        val day = active.filter { eventDate(it, zone) == date }
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val sleep = day.filter { it.type == "SLEEP" }
        val exercises = active.filter { it.type == "EXERCISE" && it.occurredAt < end && (it.endedAt ?: it.occurredAt) > start }
        val payment = day.filter { it.type == "PAYMENT" }
        val steps = day.filter { it.type == "STEP_SUMMARY" }.maxByOrNull { it.updatedAt }
        // Merge overlapping sessions to avoid multiple writers doubling durations.
        fun duration(list: List<Pair<Long, Long>>): Long {
            var total = 0L; var left = 0L; var right = 0L
            list.filter { it.second > it.first }.sortedBy { it.first }.forEach { (s, e) ->
                if (s > right) { total += right - left; left = s; right = e } else right = maxOf(right, e)
            }
            return (total + right - left) / 60_000
        }
        val amount = payment.sumOf {
            val j = JSONObject(it.dataJson)
            if (j.optString("currency") != "KRW") 0L else j.optLong("amount") * if (j.optString("paymentKind") == "CANCELLATION") -1 else 1
        }
        return DailySummary(date.toString(), steps?.let { JSONObject(it.dataJson).getLong("count") },
            sleep.takeIf { it.isNotEmpty() }?.let { duration(it.flatMap { e ->
                val intervals = JSONObject(e.dataJson).optJSONArray("sleepIntervals")
                if (intervals == null) listOf(e.occurredAt to (e.endedAt ?: e.occurredAt))
                else (0 until intervals.length()).map { i -> intervals.getJSONArray(i).let { pair -> pair.getLong(0) to pair.getLong(1) } }
            }) },
            exercises.takeIf { it.isNotEmpty() }?.let { duration(it.map { e -> maxOf(start, e.occurredAt) to minOf(end, e.endedAt ?: e.occurredAt) }) },
            payment.size, amount, day.count { it.type == "CALENDAR" }, day.count { it.type == "DELIVERY" }, day.count { it.type == "RESERVATION" },
            JSONObject().put("version", 1).put("zone", zone.id).put("healthMissingIsUnknown", true).toString(), System.currentTimeMillis())
    }
}

object BriefingBuilder {
    fun build(yesterday: DailySummary?, today: DailySummary?, history: List<Long>, morning: Int): String = buildString {
        appendLine("좋은 아침이에요.")
        appendLine("어제 종료된 수면: ${yesterday?.sleepMinutes?.let { "${it / 60}시간 ${it % 60}분" } ?: "기록 없음"}")
        appendLine("어제 걸음수: ${yesterday?.stepCount?.let { "${it}보" } ?: "미수집"}")
        appendLine("어제 운동: ${yesterday?.exerciseMinutes?.let { "${it}분" } ?: "기록 없음"}")
        appendLine("저장된 오늘 일정: ${today?.calendarCount ?: 0}개 (오전 ${morning}개)")
        if (history.size >= 3 && yesterday?.sleepMinutes != null) {
            val diff = yesterday.sleepMinutes - history.average().toLong()
            appendLine("이전 7일 중 ${history.size}일 평균보다 수면이 ${kotlin.math.abs(diff)}분 ${if (diff >= 0) "길어요" else "짧아요"}.")
        } else appendLine("수면 비교에 필요한 기록이 부족해요.")
        append("권한이나 수집 중단으로 빠진 데이터가 있을 수 있어요. 수집 상태를 함께 확인해 주세요.")
    }
}
