package com.lifedashboard

import org.json.JSONObject

val notificationClassifications = linkedMapOf(
    "PAYMENT" to "결제", "DELIVERY" to "배송", "RESERVATION" to "예약",
    "ADVERTISEMENT" to "광고", "NOTIFICATION" to "기타"
)

/** User choice belongs to the normalized event; external raw data remains untouched. */
fun applyManualClassification(parsed: ParsedEvent, raw: JSONObject, choice: JSONObject?): ParsedEvent {
    if (choice == null) return parsed
    val type = choice.getString("type")
    require(type in notificationClassifications)
    val data = JSONObject(parsed.data.toString()).put("manualClassification", choice)
    val category = when (type) {
        "PAYMENT" -> "FINANCE"
        "DELIVERY", "RESERVATION" -> "LIFE"
        "ADVERTISEMENT" -> "ADVERTISEMENT"
        else -> "COMMUNICATION"
    }
    val title = if (type == parsed.type) parsed.title else raw.optString("title").ifBlank { notificationClassifications.getValue(type) }
    val summary = if (type == "PAYMENT") {
        val amount = choice.getLong("amount")
        require(amount > 0)
        val kind = choice.getString("paymentKind")
        require(kind == "APPROVAL" || kind == "CANCELLATION")
        data.put("amount",amount).put("currency","KRW").put("paymentKind",kind)
        "${if (kind == "CANCELLATION") "취소" else "승인"} ${amount}원"
    } else if (type == parsed.type) parsed.summary else raw.optString("text")
    return parsed.copy(type = type, category = category, title = title, summary = summary, data = data,
        status = if (type == parsed.type) parsed.status else "ACTIVE",
        tags = listOf(notificationClassifications.getValue(type)),
        entities = if (type == parsed.type) parsed.entities else emptyMap())
}
