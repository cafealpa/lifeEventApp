package com.lifedashboard

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class DomainTest {
    private fun parse(text: String, title: String = "알림", grouped: Boolean = false) = NotificationParser().parse(RawEvent("r", "NOTIFICATION", "n", 1, 0, 0,
        JSONObject().put("title", title).put("text", text).put("groupSummary", grouped).toString(), "")).single()

    @Test fun paymentExcludesBalanceAndExtractsMerchant() {
        val p = parse("현대카드 승인 5,900원\n가맹점: 테스트카페\n잔액 50,000원")
        assertEquals("PAYMENT", p.type); assertEquals(5900L, p.data.getLong("amount")); assertEquals("테스트카페", p.data.getString("merchant"))
    }
    @Test fun regionalCurrencySeparatesUnitlessPaymentAndIncentive() {
        for (unit in listOf("", "원")) {
            val p = parse("테스트학원 테스트지역화폐 인센티브 10,800$unit", "결제 완료 120,000$unit")
            assertEquals("PAYMENT", p.type)
            assertEquals(120000L, p.data.getLong("amount"))
            assertEquals("테스트학원", p.data.getString("merchant"))
            assertEquals("테스트지역화폐", p.data.getString("paymentProvider"))
            assertEquals(10800L, p.data.getLong("incentiveAmount"))
            assertFalse(p.data.has("cardCompany"))
        }
    }
    @Test fun regionalCurrencyKeepsExistingLabelledPaymentFormat() {
        val p = parse("테스트지역화폐\n가맹점: 테스트카페", "결제 완료 5,900원")
        assertEquals("PAYMENT",p.type)
        assertEquals(5900L,p.data.getLong("amount"))
        assertEquals("테스트카페",p.data.getString("merchant"))
    }
    @Test fun regionalCurrencySupportsBodyHeaderAndCancellation() {
        val p = parse("결제 취소 120,000 /내용: 테스트학원 테스트지역화폐 인센티브 10,800")
        assertEquals("PAYMENT", p.type)
        assertEquals("CANCELLATION", p.data.getString("paymentKind"))
        assertEquals("테스트학원", p.data.getString("merchant"))
    }
    @Test fun regionalCurrencyDoesNotGuessUnlabelledOrMalformedAmounts() {
        listOf("결제 완료 120,00", "결제 완료 120,000.50", "결제 완료 120,000 USD", "결제 완료 120,000 130,000", "결제 완료", "충전 완료 120,000", "결제 예정 120,000", "결제 실패 120,000").forEach { title ->
            assertEquals(title, "NOTIFICATION", parse("테스트지역화폐 인센티브 10,800원", title).type)
        }
        assertEquals("NOTIFICATION", parse("결제 완료 시 인센티브 10,800원 혜택", "테스트지역화폐").type)
        assertEquals("NOTIFICATION", parse("테스트지역화폐 인센티브 10,800", "결제 완료 120,000", grouped = true).type)
    }
    @Test fun paymentCancellationIsSeparateNegativeMovement() {
        val p = parse("현대카드 승인취소 5,900원")
        assertEquals("CANCELLATION", p.data.getString("paymentKind"))
    }
    @Test fun declinesAdsAndAmbiguousAmountsStayNotifications() {
        listOf("카드 승인 거절 5,900원", "결제 예정 10,000원", "결제완료 1,000원 2,000원", "포인트 5,000원 지급").forEach { assertEquals(it, "NOTIFICATION", parse(it).type) }
    }
    @Test fun groupSummaryDoesNotCreatePayment() { assertEquals("NOTIFICATION", parse("카드 승인 5,900원", grouped = true).type) }
    @Test fun deliveryTrackingAndState() {
        val p = parse("CJ대한통운 배송 출발\n운송장번호: 123456789012")
        assertEquals("DELIVERY", p.type); assertEquals("123456789012", p.data.getString("trackingNumber")); assertEquals("OUT_FOR_DELIVERY", p.data.getString("deliveryStatus"))
    }
    @Test fun reservationCancelledAndMissingDateNotInvented() {
        val p = parse("호텔 예약 취소\n장소: 테스트호텔\n예약번호: ABC123")
        assertEquals("CANCELLED", p.status); assertFalse(p.data.has("dateText")); assertEquals("ABC123", p.data.getString("reservationNumber"))
    }
    private val zone = ZoneId.of("Asia/Seoul")
    private val date = LocalDate.of(2026, 10, 7)
    private fun time(day: String) = LocalDateTime.parse(day).atZone(zone).toInstant().toEpochMilli()
    private fun event(id: String, type: String, start: String, end: String? = null, data: String = "{}", status: String = "ACTIVE") = LifeEvent(id,type,"HEALTH",time(start),end?.let(::time),type,"","TEST",id,null,data,0.5,0,0,status)
    @Test fun stepsUsesSummaryNotRawRecords() {
        val events = listOf(event("raw","STEP","2026-10-07T08:00",data="{\"count\":1000}"),event("summary","STEP_SUMMARY","2026-10-07T00:00",data="{\"date\":\"2026-10-07\",\"count\":1200}"))
        assertEquals(1200L, SummaryCalculator.calculate(date,events,zone).stepCount)
    }
    @Test fun exerciseSplitsMidnightAndMergesOverlap() {
        val events = listOf(event("1","EXERCISE","2026-10-06T23:30","2026-10-07T00:30"),event("2","EXERCISE","2026-10-07T00:10","2026-10-07T00:40"))
        assertEquals(40L,SummaryCalculator.calculate(date,events,zone).exerciseMinutes)
        assertEquals(30L,SummaryCalculator.calculate(date.minusDays(1),events,zone).exerciseMinutes)
    }
    @Test fun sleepBelongsToEndDateAndMissingIsNull() {
        val e = event("s","SLEEP","2026-10-06T23:00","2026-10-07T06:00")
        assertEquals(420L, SummaryCalculator.calculate(date,listOf(e),zone).sleepMinutes)
        assertNull(SummaryCalculator.calculate(date.minusDays(1),listOf(e),zone).sleepMinutes)
    }
    @Test fun cancelledCalendarAndDeletedRecordsExcluded() {
        val events = listOf(event("1","CALENDAR","2026-10-07T09:00",status="CANCELLED"),event("2","CALENDAR","2026-10-07T10:00",status="DELETED"))
        assertEquals(0,SummaryCalculator.calculate(date,events,zone).calendarCount)
    }
    @Test fun cancellationDeductsOnlyKrw() {
        val events = listOf(event("1","PAYMENT","2026-10-07T09:00",data="{\"amount\":5900,\"currency\":\"KRW\",\"paymentKind\":\"APPROVAL\"}"),event("2","PAYMENT","2026-10-07T10:00",data="{\"amount\":1900,\"currency\":\"KRW\",\"paymentKind\":\"CANCELLATION\"}"),event("3","PAYMENT","2026-10-07T11:00",data="{\"amount\":100,\"currency\":\"USD\"}"))
        assertEquals(4000L,SummaryCalculator.calculate(date,events,zone).paymentAmount)
    }
    @Test fun insufficientHistoryDoesNotInventAverage() { assertTrue(BriefingBuilder.build(null,null,listOf(300),0).contains("기록이 부족")) }
    @Test fun sleepStagesExcludeAwakeIntervals() {
        val start = time("2026-10-06T23:00"); val middle = time("2026-10-07T02:00"); val end = time("2026-10-07T06:00")
        val e = event("s","SLEEP","2026-10-06T23:00","2026-10-07T06:00",data="{\"sleepIntervals\":[[$start,$middle],[${middle+3_600_000},$end]]}")
        assertEquals(360L,SummaryCalculator.calculate(date,listOf(e),zone).sleepMinutes)
    }
}
