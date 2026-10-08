package com.lifedashboard

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ParserRulesTest {
    private fun parse(body: String, rules: List<ParserRule> = emptyList(), title: String = "알림", pkg: String = "example.shop", grouped: Boolean = false): ParsedEvent {
        val raw = RawEvent("r", "NOTIFICATION", "key", 1, 0, 0, JSONObject().put("title", title).put("text", body).put("package", pkg).put("groupSummary", grouped).toString(), "")
        return NotificationParser { ParserRuleSet(2, rules) }.parse(raw).single()
    }
    private val payment = ParserRule(name = "결제 안내", phrase = "완료", type = "PAYMENT")

    @Test fun completedPaymentWithDeliveryInformationUsesLabelledAmount() {
        val result = parse("테스트쇼핑에서 결제가 완료되었습니다.\n배송 예정 안내\n상품금액 15,000원\n결제금액 12,300원\n취소 안내는 주문 페이지를 확인하세요")
        assertEquals("PAYMENT", result.type)
        assertEquals(12300, result.data.getLong("amount"))
        assertEquals("APPROVAL", result.data.getString("paymentKind"))
        assertEquals("NOTIFICATION", parse("배송 안내\n결제가 완료되었습니다").type)
        assertEquals("DELIVERY", parse("택배 배송이 완료되었습니다").type)
        assertEquals("NOTIFICATION", parse("결제가 실패했습니다. 결제금액 12,300원").type)
        assertEquals("NOTIFICATION", parse("결제 완료 시 결제금액 12,300원 혜택").type)
    }
    @Test fun scopePrefixExclusionAndPriorityAreRespected() {
        val scoped = payment.copy(packageName = "example.shop", field = "TITLE", match = "PREFIX", phrase = "완료", exclude = "실패")
        assertEquals("PAYMENT", parse("결제금액 12,300원", listOf(scoped), title = "완료 안내").type)
        assertEquals("NOTIFICATION", parse("완료 결제금액 12,300원", listOf(scoped)).type)
        assertEquals("NOTIFICATION", parse("결제금액 12,300원 실패", listOf(scoped), title = "완료 안내").type)
        assertEquals("NOTIFICATION", parse("결제금액 12,300원", listOf(scoped), title = "완료 안내", pkg = "other.app").type)
        val other = payment.copy(id = "other", type = "RESERVATION")
        assertEquals("RESERVATION", parse("완료 결제금액 12,300원", listOf(other, payment)).type)
        assertEquals("PAYMENT", parse("완료 결제금액 12,300원", listOf(other.copy(enabled = false), payment)).type)
        assertEquals("ADVERTISEMENT", parse("(광고) 완료 결제금액 12,300원", listOf(payment)).type)
        assertEquals("NOTIFICATION", parse("완료 결제금액 12,300원", listOf(payment), grouped = true).type)
    }
    @Test fun paymentRuleDoesNotGuessMissingMalformedOrAmbiguousAmounts() {
        listOf("", "결제금액 12,30원", "결제금액 -100원", "결제금액 100.5원", "결제금액 0원", "결제금액 1,000원 결제금액 2,000원", "결제금액 100 USD").forEach {
            val result = parse("완료 $it", listOf(payment))
            assertEquals(it, "NOTIFICATION", result.type)
            assertTrue(result.data.has("ruleWarning"))
            assertFalse(result.data.has("amount"))
        }
        val result = parse("완료 결제금액: 1,000원 배송비 500원", listOf(payment.copy(paymentKind = "CANCELLATION")))
        assertEquals(1000, result.data.getLong("amount"))
        assertEquals("CANCELLATION", result.data.getString("paymentKind"))
    }
    @Test fun ruleValidationAndRoundTrip() {
        payment.validate()
        assertEquals(payment, ParserRule.from(payment.json()))
        listOf(payment.copy(name = ""), payment.copy(phrase = " "), payment.copy(amountLabel = ""), payment.copy(type = "BOGUS")).forEach {
            try { it.validate(); fail("Invalid rule accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
