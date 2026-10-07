package com.lifedashboard

import org.json.JSONObject

data class ParsedEvent(val type: String, val category: String, val title: String, val summary: String,
    val data: JSONObject, val status: String = "ACTIVE", val tags: List<String> = emptyList(), val entities: Map<String, String> = emptyMap())

interface EventParser { fun parse(raw: RawEvent): List<ParsedEvent> }

class NotificationParser : EventParser {
    override fun parse(raw: RawEvent): List<ParsedEvent> {
        val json = JSONObject(raw.rawJson)
        val title = json.optString("title")
        val body = json.optString("text")
        val text = "$title\n$body"
        val data = JSONObject().put("schemaVersion", 1).put("parserVersion", VERSION).put("package", json.optString("package"))
        if (json.optBoolean("groupSummary") || json.optBoolean("ongoing")) {
            return listOf(ParsedEvent("NOTIFICATION", "COMMUNICATION", title.ifBlank { "알림" }, body, data.put("suppressed", true)))
        }
        val approval = Regex("승인|결제\\s*(?:완료|취소)|일시불|할부|체크카드")
        val card = Regex("(현대|삼성|신한|국민|KB|롯데|하나|우리|농협|NH|BC|비씨)\\s*카드").find(text)?.value
        val amounts = Regex("([0-9][0-9,]*)\\s*원").findAll(text).filter {
            !text.substring(maxOf(0, it.range.first - 12), it.range.first).contains(Regex("잔액|누적|한도|포인트|할인|캐시백"))
        }.toList()
        if (approval.containsMatchIn(text) && amounts.size == 1 && !Regex("승인\\s*거절|결제\\s*실패|승인\\s*실패|예정|혜택|이벤트").containsMatchIn(text)) {
            val amount = amounts.single().groupValues[1].replace(",", "").toLongOrNull()
            if (amount != null) {
                val cancelled = Regex("취소").containsMatchIn(text)
                val merchant = Regex("(?:가맹점|사용처|이용처)\\s*[:：]\\s*([^\\n]+)").find(text)?.groupValues?.get(1)?.trim()
                data.put("amount", amount).put("currency", "KRW").put("paymentKind", if (cancelled) "CANCELLATION" else "APPROVAL")
                data.put("cardCompany", card).put("merchant", merchant)
                return listOf(ParsedEvent("PAYMENT", "FINANCE", merchant ?: card ?: "카드 결제", "${if (cancelled) "취소" else "승인"} ${amount}원", data,
                    tags = listOf("결제"), entities = listOfNotNull(merchant?.let { "COMPANY" to it }).toMap()))
            }
        }
        if (Regex("배송|배달|택배").containsMatchIn(text) && Regex("출발|완료|도착|집화|배송 중|배송중").containsMatchIn(text)) {
            val carrier = Regex("CJ대한통운|한진택배|롯데택배|우체국|로젠택배|쿠팡").find(text)?.value
            val tracking = Regex("(?:송장|운송장)(?:번호)?\\s*[:：]?\\s*([0-9-]{8,})").find(text)?.groupValues?.get(1)
            val status = when { text.contains("예정") -> "EXPECTED"; text.contains("완료") -> "DELIVERED"; text.contains("출발") -> "OUT_FOR_DELIVERY"; else -> "IN_TRANSIT" }
            data.put("carrier", carrier).put("trackingNumber", tracking).put("deliveryStatus", status)
            data.put("product", Regex("상품(?:명)?\\s*[:：]\\s*([^\\n]+)").find(text)?.groupValues?.get(1))
            data.put("expectedArrival", Regex("[^\\n]*(?:도착 예정|오늘 도착)[^\\n]*").find(text)?.value)
            return listOf(ParsedEvent("DELIVERY", "LIFE", carrier ?: "배송 알림", body, data, tags = listOf("배송"), entities = listOfNotNull(carrier?.let { "COMPANY" to it }).toMap()))
        }
        if (Regex("예약|예매").containsMatchIn(text) && Regex("완료|확정|접수|취소").containsMatchIn(text)) {
            val state = when { text.contains("취소") && text.contains("접수") -> "CANCEL_REQUESTED"; text.contains("취소") -> "CANCELLED"; text.contains("접수") -> "REQUESTED"; else -> "CONFIRMED" }
            val place = Regex("(?:장소|업체|병원|호텔)\\s*[:：]\\s*([^\\n]+)").find(text)?.groupValues?.get(1)
            val kind = listOf("호텔", "항공", "식당", "병원", "공연").firstOrNull { text.contains(it) } ?: "기타"
            data.put("reservationStatus", state).put("kind", kind).put("place", place)
                .put("reservationNumber", Regex("(?:예약|예매)번호\\s*[:：]?\\s*([A-Za-z0-9-]+)").find(text)?.groupValues?.get(1))
                .put("dateText", Regex("\\d{4}[./-]\\d{1,2}[./-]\\d{1,2}").find(text)?.value)
                .put("timeText", Regex("\\d{1,2}:\\d{2}").find(text)?.value)
            return listOf(ParsedEvent("RESERVATION", "LIFE", place ?: "예약 알림", body, data, if (state == "CANCELLED") "CANCELLED" else "ACTIVE", listOf("예약", kind), listOfNotNull(place?.let { "PLACE" to it }).toMap()))
        }
        return listOf(ParsedEvent("NOTIFICATION", "COMMUNICATION", title.ifBlank { "알림" }, body, data))
    }
    companion object { const val VERSION = 1 }
}
