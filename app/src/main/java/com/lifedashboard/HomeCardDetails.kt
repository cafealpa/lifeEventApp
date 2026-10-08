package com.lifedashboard

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object HomeCardDetails {
    fun schedule(events: List<LifeEvent>, now: Long, zone: ZoneId): String {
        val active = events.filter { it.status == "ACTIVE" }
        val timed = active.filter { it.calendarDate == null }
        val upcoming = timed.filter { it.occurredAt > now }.sortedBy { it.occurredAt }
        val ongoing = timed.filter { it.occurredAt <= now && (it.endedAt ?: it.occurredAt) > now }.minByOrNull { it.endedAt ?: Long.MAX_VALUE }
        val next = ongoing ?: upcoming.firstOrNull()
        return "남은 일정 ${upcoming.size}개" + (next?.let {
            "\n${if (it == ongoing) "진행 중" else Instant.ofEpochMilli(it.occurredAt).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))} · ${it.title}"
        } ?: "\n이후 시간 일정 없음") + active.count { it.calendarDate != null }.takeIf { it > 0 }?.let { "\n종일 일정 ${it}개" }.orEmpty()
    }

    fun delivery(events: List<LifeEvent>): String {
        val deliveries = events.filter { it.type == "DELIVERY" && it.status == "ACTIVE" }
        val latest = deliveries.maxByOrNull { it.occurredAt } ?: return "저장된 배송 알림 없음"
        val state = when (JSONObject(latest.dataJson).optString("deliveryStatus")) {
            "DELIVERED" -> "배송 완료"; "EXPECTED" -> "도착 예정"; "OUT_FOR_DELIVERY" -> "배송 출발"; "IN_TRANSIT" -> "배송 중"; else -> "상태 미확인"
        }
        val completed = deliveries.count { JSONObject(it.dataJson).optString("deliveryStatus") == "DELIVERED" }
        return "완료 알림 ${completed}건 · 최근 $state\n${latest.title}"
    }
}
