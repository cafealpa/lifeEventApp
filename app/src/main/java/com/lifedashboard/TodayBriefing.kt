package com.lifedashboard

import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

object TodayBriefing {
    const val VERSION = 2
    fun inputs(events: List<LifeEvent>): String = events.filter { it.type in setOf("CALENDAR", "DELIVERY", "PAYMENT") }
        .sortedBy { it.id }.joinToString("\n") { JSONObject().put("id", it.id).put("type", it.type).put("title", it.title).put("summary", it.summary).put("start", it.occurredAt).put("end", it.endedAt).put("status", it.status).put("deliveryStatus", JSONObject(it.dataJson).optString("deliveryStatus")).toString() }
    fun build(today: LocalDate, zone: ZoneId, now: Long, summary: DailySummary?, events: List<LifeEvent>): JSONObject {
        val currentTime = Instant.ofEpochMilli(now).atZone(zone)
        val greeting = when (currentTime.hour) { in 0..5 -> "고요한 새벽이에요"; in 6..11 -> "좋은 아침이에요"; in 12..17 -> "오늘도 차곡차곡"; else -> "하루를 돌아볼 시간이에요" }
        val schedules = events.filter { it.type == "CALENDAR" && it.status == "ACTIVE" }
        val timed = schedules.filter { it.calendarDate == null }
        val upcoming = timed.filter { it.occurredAt > now }.sortedBy { it.occurredAt }
        val ongoing = timed.filter { it.occurredAt <= now && (it.endedAt ?: it.occurredAt) > now }.minByOrNull { it.endedAt ?: Long.MAX_VALUE }
        val next = ongoing ?: upcoming.firstOrNull()
        val deliveries = events.filter { it.type == "DELIVERY" && it.status == "ACTIVE" }
        val completed = deliveries.count { JSONObject(it.dataJson).optString("deliveryStatus") == "DELIVERED" }
        val latestPayment = events.filter { it.type == "PAYMENT" && it.status == "ACTIVE" }.maxByOrNull { it.occurredAt }
        val latestDelivery = deliveries.maxByOrNull { it.occurredAt }
        val deliveryState = latestDelivery?.let { when(JSONObject(it.dataJson).optString("deliveryStatus")) {
            "DELIVERED" -> "배송 완료"; "EXPECTED" -> "도착 예정"; "OUT_FOR_DELIVERY" -> "배송 출발"; "IN_TRANSIT" -> "배송 중"; else -> "상태 미확인"
        } }
        val sections = JSONArray()
        fun section(icon: String, label: String, value: String, detail: String) { sections.put(JSONObject().put("icon", icon).put("label", label).put("value", value).put("detail", detail)) }
        fun number(value: Long) = String.format(Locale.KOREAN, "%,d", value)
        val allDay = schedules.count { it.calendarDate != null }
        section("CALENDAR", "남은 일정", "${upcoming.size}개", next?.let {
            "${if (it == ongoing) "진행 중" else Instant.ofEpochMilli(it.occurredAt).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))} · ${it.title}"
        } ?: if (allDay > 0) "종일 일정 ${allDay}개 · 오늘 전체 ${schedules.size}개" else "저장된 오늘 일정 ${schedules.size}개 · 이후 일정 없음")
        section("STEP_SUMMARY", "오늘 걸음수", summary?.stepCount?.let { "${number(it)}보" } ?: "기록 없음", summary?.exerciseMinutes?.let { "오늘 운동 ${it}분" } ?: "운동 기록 없음")
        section("PAYMENT", "오늘 결제", summary?.let { "${number(it.paymentAmount)}원" } ?: "집계 대기", summary?.let { "결제·취소 알림 ${it.paymentCount}건 · 취소 반영 합계" + (latestPayment?.let { payment -> "\n최근 · ${payment.title}" } ?: "") } ?: "저장된 결제 기록을 확인하고 있어요")
        section("DELIVERY", "오늘 배송 알림", "${deliveries.size}건", if (deliveries.isEmpty()) "저장된 배송 알림 없음" else "완료 알림 ${completed}건 · 최근 ${deliveryState}")
        section("SLEEP", "오늘 종료된 수면", summary?.sleepMinutes?.let { "${it / 60}시간 ${it % 60}분" } ?: "기록 없음", "오늘 종료된 수면 기록 기준")
        val nextDay = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val boundaries = listOf(6,12,18).map { today.atTime(it,0).atZone(zone).toInstant().toEpochMilli() } + timed.flatMap { listOfNotNull(it.occurredAt, it.endedAt) } + nextDay
        return JSONObject().put("greeting", greeting).put("sections", sections)
            .put("nextChangeAt", boundaries.filter { it > now }.minOrNull() ?: nextDay)
            .put("eventInputs", inputs(events)).put("generatedAt", now)
    }
    fun text(data: JSONObject): String = buildString {
        appendLine(data.getString("greeting"))
        val sections = data.getJSONArray("sections")
        for (i in 0 until sections.length()) {
            val section = sections.getJSONObject(i)
            appendLine("${section.getString("label")}: ${section.getString("value")}")
            appendLine(section.getString("detail"))
        }
        append("저장된 기록 기준이며, 수집되지 않은 데이터는 포함되지 않아요.")
    }
}
