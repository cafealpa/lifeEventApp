package com.lifedashboard

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import java.time.temporal.TemporalAdjusters
import java.util.UUID

val dashboardTypes = linkedMapOf("CALENDAR" to "일정", "SLEEP" to "수면", "STEP_SUMMARY" to "걸음", "EXERCISE" to "운동", "PAYMENT" to "결제", "DELIVERY" to "배송", "RESERVATION" to "예약", "PLACE_VISIT" to "방문", "NOTIFICATION" to "기타 알림", "ADVERTISEMENT" to "광고")
val basicCards = linkedMapOf("CALENDAR" to "오늘 일정", "SLEEP" to "오늘 종료된 수면", "STEP_SUMMARY" to "오늘 걸음수", "EXERCISE" to "오늘 운동", "SCHEDULE_LIST" to "일정 목록", "PAYMENT" to "오늘 결제", "DELIVERY" to "오늘 배송 알림", "RESERVATION" to "오늘 예약 알림", "PLACE_VISIT" to "오늘 방문")
enum class CardPeriod(val label: String) {
    TODAY("오늘"), YESTERDAY("어제"), WEEK("이번 주"), MONTH("이번 달"), DAYS7("최근 7일"), DAYS30("최근 30일");
    fun range(today: LocalDate): Pair<LocalDate, LocalDate> = when (this) {
        TODAY -> today to today.plusDays(1)
        YESTERDAY -> today.minusDays(1) to today
        WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) to today.plusDays(1)
        MONTH -> today.withDayOfMonth(1) to today.plusDays(1)
        DAYS7 -> today.minusDays(6) to today.plusDays(1)
        DAYS30 -> today.minusDays(29) to today.plusDays(1)
    }
}
enum class CardMetric(val label: String) { COUNT("기록 수"), DAYS("기록이 있는 날짜 수"), ATTENDANCE("진입·이탈이 모두 있는 출석일 수"), SUM("합계"), AVERAGE("기록 있는 날의 일평균"), LATEST("최근 기록") }
fun cardMetrics(type: String): List<CardMetric> = listOf(CardMetric.COUNT, CardMetric.DAYS, CardMetric.LATEST) + when (type) {
    "PLACE_VISIT" -> listOf(CardMetric.ATTENDANCE)
    "PAYMENT" -> listOf(CardMetric.SUM)
    "SLEEP", "EXERCISE", "STEP_SUMMARY" -> listOf(CardMetric.SUM, CardMetric.AVERAGE)
    else -> emptyList()
}
data class CardQuery(val type: String = "PAYMENT", val period: CardPeriod = CardPeriod.TODAY,
    val metric: CardMetric = CardMetric.COUNT, val merchant: String = "", val cardCompany: String = "",
    val packageName: String = "", val text: String = "", val placeName: String = "", val placeId: String = "",
    val datasetId: String = "", val includeLegacy: Boolean = false, val transition: String = "") {
    fun validate() {
        require(type in dashboardTypes && metric in cardMetrics(type)) { "지원하지 않는 카드 조건이에요" }
        require(transition in listOf("", "ENTER", "EXIT"))
        require(metric != CardMetric.ATTENDANCE || transition.isEmpty()) { "출석일은 진입과 이탈 모두 필요해요" }
        require(placeId.isEmpty() || datasetId.isNotBlank())
        require(listOf(merchant,cardCompany,packageName,text,placeName,placeId,datasetId).all { it.length <= 300 })
    }
    fun json() = JSONObject().put("type",type).put("period",period.name).put("metric",metric.name)
        .put("merchant",merchant).put("cardCompany",cardCompany).put("packageName",packageName).put("text",text)
        .put("placeName",placeName).put("placeId",placeId).put("datasetId",datasetId).put("includeLegacy",includeLegacy).put("transition",transition)
    companion object {
        fun read(j: JSONObject) = CardQuery(j.getString("type"),CardPeriod.valueOf(j.getString("period")),CardMetric.valueOf(j.getString("metric")),
            j.optString("merchant"),j.optString("cardCompany"),j.optString("packageName"),j.optString("text"),j.optString("placeName"),j.optString("placeId"),j.optString("datasetId"),j.optBoolean("includeLegacy"),j.optString("transition")).also { it.validate() }
    }
}
data class DashboardCardSpec(val id: String = UUID.randomUUID().toString(), val kind: String, val title: String, val halfWidth: Boolean = false, val query: CardQuery? = null) {
    fun json() = JSONObject().put("id",id).put("kind",kind).put("title",title).put("halfWidth",halfWidth).put("query",query?.json())
}
class DashboardCardStore(context: Context) {
    private val prefs = context.getSharedPreferences("dashboard_cards", Context.MODE_PRIVATE)
    val error = MutableStateFlow<String?>(null)
    val cards = MutableStateFlow(load())
    private fun load(): List<DashboardCardSpec> = try {
        prefs.getString("cards",null)?.let(::decode) ?: defaults()
    } catch (_: Exception) { error.value = "카드 설정을 읽지 못했어요. 기본 구성 복원으로 다시 설정할 수 있어요."; defaults() }
    @Synchronized fun save(value: List<DashboardCardSpec>) {
        require(value.size <= 20) { "카드는 최대 20개까지 만들 수 있어요" }
        val encoded = JSONObject().put("version",1).put("cards",JSONArray(value.map { it.json() })).toString()
        decode(encoded)
        check(prefs.edit().putString("cards",encoded).commit()) { "카드 설정을 저장하지 못했어요" }
        cards.value = value; error.value = null
    }
    companion object {
        fun defaults() = basicCards.filterKeys { it != "PLACE_VISIT" }.map { (kind,title) -> DashboardCardSpec("basic:$kind",kind,title,kind in setOf("CALENDAR","SLEEP","STEP_SUMMARY","EXERCISE")) }
        fun decode(value: String): List<DashboardCardSpec> {
            val root = JSONObject(value); require(root.getInt("version") == 1)
            val rows = root.getJSONArray("cards"); require(rows.length() <= 20)
            return (0 until rows.length()).map { i -> rows.getJSONObject(i).let { j ->
                DashboardCardSpec(j.getString("id"),j.getString("kind"),j.getString("title"),j.optBoolean("halfWidth"),j.optJSONObject("query")?.let(CardQuery::read)).also {
                    require(it.id.isNotBlank() && it.title.isNotBlank() && it.title.length <= 60)
                    require(it.kind in basicCards || (it.kind == "CUSTOM" && it.query != null))
                }
            } }.also { require(it.map { c -> c.id }.distinct().size == it.size) }
        }
    }
}
data class CardResult(val value: String = "조회 중", val note: String = "", val records: List<LifeEvent> = emptyList(), val error: Boolean = false)
data class VisitPlace(val datasetId: String, val placeId: String, val name: String)
class DashboardQueryRepository(private val dao: LifeDao) {
    fun observe(query: CardQuery, today: LocalDate, zone: ZoneId): Flow<CardResult> {
        query.validate()
        val (start,end) = query.period.range(today)
        return dao.observeCardEvents(start.atStartOfDay(zone).toInstant().toEpochMilli(),end.atStartOfDay(zone).toInstant().toEpochMilli(),start.toString(),end.toString(),query.type)
            .map { CardCalculator.calculate(query,it,today,zone) }
            .catch { e -> if (e is kotlinx.coroutines.CancellationException) throw e; emit(CardResult("조회 실패","저장 데이터 조회를 다시 시도해 주세요",error=true)) }
            .flowOn(Dispatchers.Default)
    }
}
object CardCalculator {
    fun calculate(query: CardQuery, input: List<LifeEvent>, today: LocalDate, zone: ZoneId): CardResult {
        query.validate()
        val (from,until) = query.period.range(today)
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli(); val end = until.atStartOfDay(zone).toInstant().toEpochMilli()
        val records = input.filter { e ->
            val j = JSONObject(e.dataJson)
            e.status == "ACTIVE" && e.type == query.type &&
                (if (e.type == "EXERCISE") e.occurredAt < end && (e.endedAt ?: e.occurredAt) > start else eventDate(e,zone).let { !it.isBefore(from) && it.isBefore(until) }) &&
                (query.type != "PLACE_VISIT" || e.sourceType == "SPOTTRACE") &&
                (query.merchant.isBlank() || j.optString("merchant").equals(query.merchant.trim(),true)) &&
                (query.cardCompany.isBlank() || j.optString("cardCompany").equals(query.cardCompany.trim(),true)) &&
                (query.packageName.isBlank() || j.optString("package") == query.packageName.trim()) &&
                (query.text.isBlank() || "${e.title}\n${e.summary.orEmpty()}".contains(query.text.trim(),true)) &&
                (query.datasetId.isBlank() || j.optString("datasetId") == query.datasetId) &&
                (if (query.placeId.isNotBlank()) j.optString("placeId") == query.placeId ||
                    (query.includeLegacy && j.optString("placeId").isBlank() && j.optString("placeName") == query.placeName)
                 else query.placeName.isBlank() || j.optString("placeName") == query.placeName.trim()) &&
                (query.transition.isBlank() || j.optString("transition") == query.transition)
        }.sortedWith(compareByDescending<LifeEvent> { if (it.type == "SLEEP") it.endedAt ?: it.occurredAt else it.occurredAt }.thenByDescending { it.id })
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { it < until }.toList()
        val note = "${from} ~ ${until.minusDays(1)} · 저장된 기록 ${records.size}건"
        val value = when (query.metric) {
            CardMetric.COUNT -> "${records.size}건"
            CardMetric.DAYS -> "${records.flatMap { affectedDates(it,zone) }.filter { it >= from && it < until }.distinct().size}일"
            CardMetric.ATTENDANCE -> {
                val groups = records.groupBy { e -> val j = JSONObject(e.dataJson); Triple(eventDate(e,zone),j.optString("datasetId"),j.optString("placeId").ifBlank { "name:${j.optString("placeName")}" }) }
                "${groups.filterValues { rows -> rows.map { JSONObject(it.dataJson).getString("transition") }.toSet().containsAll(setOf("ENTER","EXIT")) }.keys.map { it.first }.distinct().size}일"
            }
            CardMetric.LATEST -> records.firstOrNull()?.let { "${eventTime(it)} ${it.title}" } ?: "기록 없음"
            CardMetric.SUM, CardMetric.AVERAGE -> {
                val values = days.mapNotNull { day ->
                    val s = SummaryCalculator.calculate(day,records,zone)
                    when(query.type) { "PAYMENT" -> s.paymentAmount; "SLEEP" -> s.sleepMinutes; "EXERCISE" -> s.exerciseMinutes; "STEP_SUMMARY" -> s.stepCount; else -> null }
                }
                if (values.isEmpty()) "기록 없음" else {
                    val number = if(query.metric == CardMetric.AVERAGE) values.sum() / values.size else values.sum()
                    val unit = when(query.type) { "PAYMENT" -> "원"; "STEP_SUMMARY" -> "보"; else -> "분" }
                    "${String.format(java.util.Locale.KOREAN,"%,d",number)}$unit" + if(query.metric == CardMetric.AVERAGE) " · ${values.size}일 기준" else ""
                }
            }
        }
        return CardResult(value,note + if(query.type in setOf("ADVERTISEMENT","NOTIFICATION")) "\n보관 기간이 지난 알림은 포함되지 않아요" else "",records)
    }
}
