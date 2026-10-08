package com.lifedashboard

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ParserRule(
    val id: String = UUID.randomUUID().toString(), val name: String = "", val enabled: Boolean = true,
    val packageName: String = "", val field: String = "BOTH", val match: String = "CONTAINS",
    val phrase: String = "", val exclude: String = "", val type: String = "NOTIFICATION",
    val amountLabel: String = "결제금액", val paymentKind: String = "APPROVAL"
) {
    fun validate() {
        require(name.isNotBlank() && phrase.isNotBlank()) { "규칙 이름과 포함 문구를 입력해 주세요" }
        require(type in notificationClassifications && field in setOf("TITLE", "BODY", "BOTH") && match in setOf("CONTAINS", "PREFIX"))
        require(paymentKind in setOf("APPROVAL", "CANCELLATION"))
        require(type != "PAYMENT" || amountLabel.isNotBlank()) { "금액 앞에 오는 문구를 입력해 주세요" }
    }
    fun matches(title: String, body: String, sourcePackage: String): Boolean {
        if (!enabled || phrase.isBlank() || (packageName.isNotBlank() && packageName != sourcePackage)) return false
        if (exclude.isNotBlank() && (title.contains(exclude, true) || body.contains(exclude, true))) return false
        val fields = when (field) { "TITLE" -> listOf(title); "BODY" -> listOf(body); else -> listOf(title, body) }
        return fields.any { if (match == "PREFIX") it.startsWith(phrase, true) else it.contains(phrase, true) }
    }
    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("enabled", enabled)
        .put("packageName", packageName).put("field", field).put("match", match).put("phrase", phrase)
        .put("exclude", exclude).put("type", type).put("amountLabel", amountLabel).put("paymentKind", paymentKind)
    companion object {
        fun from(json: JSONObject) = ParserRule(json.getString("id"), json.getString("name"), json.getBoolean("enabled"),
            json.getString("packageName"), json.getString("field"), json.getString("match"), json.getString("phrase"),
            json.getString("exclude"), json.getString("type"), json.getString("amountLabel"), json.getString("paymentKind"))
    }
}
data class ParserRuleSet(val version: Long = 0, val rules: List<ParserRule> = emptyList())
class ParserRuleStore(context: Context) {
    private val prefs = context.getSharedPreferences("notification_parser_rules", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(read())
    val state = mutableState.asStateFlow()
    private fun read(): ParserRuleSet {
        val saved = prefs.getString("rules", null) ?: return ParserRuleSet()
        val json = JSONObject(saved)
        val array = json.getJSONArray("items")
        return ParserRuleSet(json.getLong("version"), (0 until array.length()).map { ParserRule.from(array.getJSONObject(it)) })
    }
    fun save(rules: List<ParserRule>) {
        rules.forEach { it.validate() }
        require(rules.map { it.id }.distinct().size == rules.size)
        val next = ParserRuleSet(mutableState.value.version + 1, rules.toList())
        val json = JSONObject().put("version", next.version).put("items", JSONArray(rules.map { it.json() }))
        check(prefs.edit().putString("rules", json.toString()).commit()) { "규칙을 저장하지 못했어요" }
        mutableState.value = next
    }
}

/** Only an explicitly labelled, unambiguous positive KRW amount is accepted. */
fun labelledPaymentAmount(text: String, label: String): Long? {
    val number = "(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)"
    val values = Regex(Regex.escape(label) + "\\s*[:：]?\\s*(" + number + ")\\s*원")
        .findAll(text).mapNotNull { it.groupValues[1].replace(",", "").toLongOrNull() }.toList()
    return values.singleOrNull()?.takeIf { it > 0 }
}
fun applyParserRule(rule: ParserRule, title: String, body: String, data: JSONObject): ParsedEvent {
    data.put("parserRuleId", rule.id).put("parserRuleName", rule.name)
    if (rule.type == "PAYMENT") {
        val amount = labelledPaymentAmount("$title\n$body", rule.amountLabel)
        if (amount == null) return ParsedEvent("NOTIFICATION", "COMMUNICATION", title.ifBlank { "알림" }, body,
            data.put("ruleWarning", "결제금액을 하나로 확인하지 못했어요. 분류 변경에서 금액을 확인해 주세요."))
        data.put("amount", amount).put("currency", "KRW").put("paymentKind", rule.paymentKind)
        return ParsedEvent("PAYMENT", "FINANCE", title.ifBlank { "결제" }, "${if (rule.paymentKind == "CANCELLATION") "취소" else "승인"} ${amount}원", data, tags = listOf("결제"))
    }
    val category = when (rule.type) { "ADVERTISEMENT" -> "ADVERTISEMENT"; "NOTIFICATION" -> "COMMUNICATION"; else -> "LIFE" }
    return ParsedEvent(rule.type, category, title.ifBlank { notificationClassifications.getValue(rule.type) }, body, data,
        tags = listOf(notificationClassifications.getValue(rule.type)))
}
