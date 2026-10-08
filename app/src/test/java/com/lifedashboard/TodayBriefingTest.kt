package com.lifedashboard

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class TodayBriefingTest {
    private val day = LocalDate.of(2026,10,8)
    private val zone = ZoneId.of("Asia/Seoul")
    private fun time(hour: Int) = day.atTime(hour,0).atZone(zone).toInstant().toEpochMilli()
    private fun event(id: String, type: String, start: Int, end: Int, data: String = "{}") = LifeEvent(id,type,"LIFE",time(start),time(end),"테스트 일정", "", "TEST",id,null,data,createdAt=1,updatedAt=1)
    private fun section(data: JSONObject, index: Int) = data.getJSONArray("sections").getJSONObject(index)
    @Test fun scheduleProgressAndGreetingChangeAtBoundaryWithoutNewData() {
        val events=listOf(event("past","CALENDAR",8,9),event("next","CALENDAR",10,11))
        val before=TodayBriefing.build(day,zone,time(9),null,events)
        assertEquals("1개",section(before,0).getString("value"))
        assertEquals(time(10),before.getLong("nextChangeAt"))
        val during=TodayBriefing.build(day,zone,time(10),null,events)
        assertEquals("0개",section(during,0).getString("value"))
        assertTrue(section(during,0).getString("detail").contains("진행 중"))
        assertEquals(time(11),during.getLong("nextChangeAt"))
        val after=TodayBriefing.build(day,zone,time(12),null,events)
        assertFalse(section(after,0).getString("detail").contains("진행 중"))
        assertNotEquals(before.getString("greeting"),after.getString("greeting"))
    }
    @Test fun todayMetricsPreserveUnknownAndNetAmountsAndDeliveryIsNotificationCount() {
        val summary=DailySummary(day.toString(),8500,390,25,2,-500,0,2,0,"{}",1)
        val delivery=event("d","DELIVERY",9,9,"{\"deliveryStatus\":\"DELIVERED\"}")
        val data=TodayBriefing.build(day,zone,time(10),summary,listOf(delivery))
        assertEquals("8,500보",section(data,1).getString("value"))
        assertEquals("-500원",section(data,2).getString("value"))
        assertEquals("1건",section(data,3).getString("value"))
        assertTrue(section(data,3).getString("detail").contains("배송 완료"))
        assertEquals("6시간 30분",section(data,4).getString("value"))
        assertEquals("기록 없음",section(TodayBriefing.build(day,zone,time(10),null,emptyList()),1).getString("value"))
    }
}
