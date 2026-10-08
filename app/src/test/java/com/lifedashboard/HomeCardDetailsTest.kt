package com.lifedashboard

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class HomeCardDetailsTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun time(hour: Int) = LocalDate.of(2026,10,8).atTime(hour,0).atZone(zone).toInstant().toEpochMilli()
    private fun event(id: String, type: String, start: Int, end: Int, data: String = "{}") =
        LifeEvent(id,type,"LIFE",time(start),time(end),"테스트",null,"TEST",id,null,data,createdAt=1,updatedAt=1)
    @Test fun scheduleChangesAtStartAndEndWithoutStoredBriefing() {
        val events = listOf(event("a","CALENDAR",10,11), event("b","CALENDAR",12,13).copy(status="CANCELLED"))
        assertTrue(HomeCardDetails.schedule(events,time(9),zone).contains("남은 일정 1개"))
        assertTrue(HomeCardDetails.schedule(events,time(10),zone).contains("진행 중"))
        assertTrue(HomeCardDetails.schedule(events,time(11),zone).contains("이후 시간 일정 없음"))
        assertFalse(HomeCardDetails.schedule(events,time(11),zone).contains("진행 중"))
    }
    @Test fun allDayIsSeparateFromRemainingTimedSchedule() {
        val events = listOf(event("a","CALENDAR",0,23).copy(calendarDate="2026-10-08"))
        val text = HomeCardDetails.schedule(events,time(9),zone)
        assertTrue(text.contains("남은 일정 0개"))
        assertTrue(text.contains("종일 일정 1개"))
    }
    @Test fun deliveryCountsNotificationsAndUsesLatestActiveStatus() {
        val events = listOf(event("a","DELIVERY",9,9,"{\"deliveryStatus\":\"DELIVERED\"}"),
            event("b","DELIVERY",10,10,"{\"deliveryStatus\":\"IN_TRANSIT\"}"),
            event("c","DELIVERY",11,11,"{\"deliveryStatus\":\"DELIVERED\"}").copy(status="CANCELLED"))
        assertTrue(HomeCardDetails.delivery(events).contains("완료 알림 1건 · 최근 배송 중"))
        assertEquals("저장된 배송 알림 없음",HomeCardDetails.delivery(emptyList()))
    }
}
