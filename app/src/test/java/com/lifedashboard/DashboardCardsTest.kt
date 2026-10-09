package com.lifedashboard

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class DashboardCardsTest {
    private val zone=ZoneId.of("Asia/Seoul")
    private val day=LocalDate.of(2026,10,8)
    private fun event(id: String,type: String,at: LocalDate=day,j: JSONObject=JSONObject(),end: Long?=null): LifeEvent = LifeEvent(id,type,"LIFE",at.atTime(9,0).atZone(zone).toInstant().toEpochMilli(),end,"예시",null,if(type=="PLACE_VISIT")"SPOTTRACE" else "TEST",id,null,j.toString(),createdAt=1,updatedAt=1)
    private fun visit(id: String,transition: String,place: String?="p",date: LocalDate=day,name: String="회사")=event(id,"PLACE_VISIT",date,JSONObject().put("datasetId","d").put("placeId",place).put("placeName",name).put("transition",transition))
    @Test fun definitionRoundTripPreservesEmptyHomeAndQueryIdentity() {
        val custom=DashboardCardSpec("stable","CUSTOM","회사 방문",true,CardQuery("PLACE_VISIT",CardPeriod.MONTH,CardMetric.DAYS,placeId="p",datasetId="d",includeLegacy=true,transition="ENTER"))
        val root=JSONObject().put("version",1).put("cards",org.json.JSONArray(listOf(custom.json())))
        assertEquals(listOf(custom),DashboardCardStore.decode(root.toString()))
        assertTrue(DashboardCardStore.decode("{\"version\":1,\"cards\":[]}").isEmpty())
    }
    @Test fun rejectsUnknownVersionAndDuplicateCardIds() {
        assertThrows(IllegalArgumentException::class.java) {DashboardCardStore.decode("{\"version\":2,\"cards\":[]}")}
        val c=DashboardCardSpec("same","PAYMENT","결제")
        assertThrows(IllegalArgumentException::class.java) {DashboardCardStore.decode(JSONObject().put("version",1).put("cards",org.json.JSONArray(listOf(c.json(),c.json()))).toString())}
    }
    @Test fun paymentFiltersAreAndCombinedAndCancellationIsNegative() {
        fun payment(id:String,amount:Long,merchant:String,kind:String="APPROVAL")=event(id,"PAYMENT",j=JSONObject().put("amount",amount).put("currency","KRW").put("merchant",merchant).put("paymentKind",kind).put("package","app"))
        val rows=listOf(payment("1",7000,"카페"),payment("2",2000,"카페","CANCELLATION"),payment("3",9000,"식당"),payment("4",1000,""))
        val result=CardCalculator.calculate(CardQuery(metric=CardMetric.SUM,merchant="카페",packageName="app"),rows,day,zone)
        assertEquals("5,000원",result.value); assertEquals(setOf("1","2"),result.records.map {it.id}.toSet())
        assertEquals("0원",CardCalculator.calculate(CardQuery(metric=CardMetric.SUM,merchant="없음"),rows,day,zone).value)
    }
    @Test fun visitsCountDaysAndAttendanceHaveDifferentSemantics() {
        val records=listOf(visit("1","ENTER"),visit("2","ENTER"),visit("3","EXIT"),visit("4","ENTER",date=day.minusDays(1)))
        val q=CardQuery("PLACE_VISIT",CardPeriod.DAYS7,transition="ENTER")
        assertEquals("3건",CardCalculator.calculate(q,records,day,zone).value)
        assertEquals("2일",CardCalculator.calculate(q.copy(metric=CardMetric.DAYS),records,day,zone).value)
        assertEquals("1일",CardCalculator.calculate(q.copy(metric=CardMetric.ATTENDANCE,transition=""),records,day,zone).value)
    }
    @Test fun attendanceNeverPairsDifferentPlacesOrDatasets() {
        val rows=listOf(visit("1","ENTER","a"),visit("2","EXIT","b"))
        assertEquals("0일",CardCalculator.calculate(CardQuery("PLACE_VISIT",metric=CardMetric.ATTENDANCE),rows,day,zone).value)
    }
    @Test fun stablePlaceFilterOnlyAddsExplicitLegacyNames() {
        val rows=listOf(visit("1","ENTER","a",name="새 이름"),visit("2","ENTER","b"),visit("3","ENTER",null))
        val q=CardQuery("PLACE_VISIT",placeId="a",datasetId="d",placeName="회사")
        assertEquals(listOf("1"),CardCalculator.calculate(q,rows,day,zone).records.map {it.id})
        assertEquals(setOf("1","3"),CardCalculator.calculate(q.copy(includeLegacy=true),rows,day,zone).records.map {it.id}.toSet())
    }
    @Test fun sleepAverageUsesRecordedDaysAndMergesOverlaps() {
        val start=day.atTime(1,0).atZone(zone).toInstant().toEpochMilli()
        fun sleep(id:String,s:Long,e:Long)=event(id,"SLEEP",end=e).copy(occurredAt=s)
        val rows=listOf(sleep("1",start,start+120*60000),sleep("2",start+60*60000,start+180*60000))
        assertEquals("180분 · 1일 기준",CardCalculator.calculate(CardQuery("SLEEP",CardPeriod.DAYS7,CardMetric.AVERAGE),rows,day,zone).value)
    }
    @Test fun exerciseCrossingMidnightClipsToSelectedDay() {
        val midnight=day.atStartOfDay(zone).toInstant().toEpochMilli()
        val row=event("1","EXERCISE",end=midnight+30*60000).copy(occurredAt=midnight-30*60000)
        assertEquals("30분",CardCalculator.calculate(CardQuery("EXERCISE",metric=CardMetric.SUM),listOf(row),day,zone).value)
    }
    @Test fun weekStartsMondayAndMonthsDoNotIncludeFutureDates() {
        assertEquals(LocalDate.of(2026,10,5) to day.plusDays(1),CardPeriod.WEEK.range(day))
        assertEquals(day.withDayOfMonth(1) to day.plusDays(1),CardPeriod.MONTH.range(day))
    }
    @Test fun deletedVisitsAndNotificationCopiesAreExcluded() {
        val row=visit("1","ENTER")
        assertEquals("0건",CardCalculator.calculate(CardQuery("PLACE_VISIT"),listOf(row.copy(status="DELETED"),row.copy(id="2",sourceType="NOTIFICATION")),day,zone).value)
    }
}
