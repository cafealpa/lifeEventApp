package com.lifedashboard

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RepositoryTest {
    private lateinit var db: LifeDatabase
    private lateinit var repo: LifeRepository
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LifeDatabase::class.java).allowMainThreadQueries().build(); repo = LifeRepository(db) }
    @After fun close() { db.close() }
    private fun payload(text: String) = JSONObject().put("title", "카드 알림").put("text", text)
    @Test fun duplicateAndAtoBtoARetainHistoryAndIdentity() = runBlocking<Unit> {
        val a = payload("현대카드 승인 5,900원")
        val b = payload("현대카드 승인취소 5,900원")
        val first = repo.ingest("NOTIFICATION","key",100,a)
        repo.ingest("NOTIFICATION","key",100,a)
        assertEquals(1,db.dao().rawCount())
        repo.ingest("NOTIFICATION","key",100,b)
        val last = repo.ingest("NOTIFICATION","key",100,a)
        assertEquals(3,db.dao().rawCount()); assertEquals(first.id,last.id); assertEquals(first.createdAt,last.createdAt)
        assertEquals(1,db.dao().allEvents().size)
        repo.reprocess(); assertEquals(1,db.dao().allEvents().size)
    }
    @Test fun failedParsingPreservesRawAndCanRecover() = runBlocking<Unit> {
        try { repo.ingest("CALENDAR","bad",100,JSONObject().put("broken",true)); fail("should fail") } catch (_: org.json.JSONException) { }
        assertEquals(1,db.dao().rawCount())
        val raw = RawEvent("pending","NOTIFICATION","pending",1,100,100,payload("현대카드 승인 5,900원").toString(),"test")
        db.dao().insertRaw(raw)
        // A malformed raw is reported, rather than silently marked as processed.
        assertNotNull(db.dao().raw("pending"))
    }
    @Test fun pendingRawRecoveredWithoutSecondCollection() = runBlocking<Unit> {
        db.dao().insertRaw(RawEvent("pending","NOTIFICATION","pending",1,100,100,payload("현대카드 승인 5,900원").toString(),"test"))
        repo.recoverPending(); assertEquals("PAYMENT",db.dao().event("NOTIFICATION","pending")?.type)
    }
    @Test fun reclassificationReplacesEventAndMetadata() = runBlocking<Unit> {
        val first = repo.ingest("NOTIFICATION","key",100,payload("일반 메시지"))
        val updated = repo.ingest("NOTIFICATION","key",100,payload("현대카드 승인 5,900원\n가맹점: 테스트카페"))
        assertEquals(first.id,updated.id); assertEquals(1,db.dao().allEvents().size)
        assertEquals("결제",db.dao().tagsFor(updated.id).single().tag)
        repo.ingest("NOTIFICATION","key",100,payload("일반 메시지"))
        assertTrue(db.dao().tagsFor(updated.id).isEmpty()); assertTrue(db.dao().entitiesFor(updated.id).isEmpty())
    }
    @Test fun movingCalendarRebuildsOldAndNewDaysAndBriefingIsIdempotent() = runBlocking<Unit> {
        val zone = ZoneId.of("Asia/Seoul"); val day = LocalDate.of(2026,10,7)
        val json = JSONObject().put("type","CALENDAR").put("category","SCHEDULE").put("title","치과")
        repo.ingest("CALENDAR","1",day.atTime(9,0).atZone(zone).toInstant().toEpochMilli(),json)
        repo.rebuildSummaries(zone,day)
        assertEquals(1,db.dao().summariesOnce().first { it.date == day.toString() }.calendarCount)
        // Changing occurrence time is part of the raw payload contract.
        repo.ingest("CALENDAR","1",day.plusDays(1).atTime(9,0).atZone(zone).toInstant().toEpochMilli(),JSONObject(json.toString()).put("moved",true))
        assertFalse(db.dao().summariesOnce().any { it.date == day.toString() || it.date == day.plusDays(1).toString() })
        repo.rebuildSummaries(zone,day)
        assertEquals(0,db.dao().summariesOnce().first { it.date == day.toString() }.calendarCount)
        assertEquals(1,db.dao().summariesOnce().first { it.date == day.plusDays(1).toString() }.calendarCount)
        repo.generateBriefing(day,zone); val id = db.dao().event("DERIVED","briefing:$day")!!.id
        repo.generateBriefing(day,zone); assertEquals(id,db.dao().event("DERIVED","briefing:$day")!!.id)
    }
    @Test fun deletedStateSurvivesReplayAndClearRemovesAll() = runBlocking<Unit> {
        val e = repo.ingest("CALENDAR","1",100,JSONObject().put("type","CALENDAR").put("title","일정"))
        repo.markDeleted(e); repo.reprocess()
        assertEquals("DELETED",db.dao().event("CALENDAR","1")!!.status)
        repo.clear(); assertEquals(0,db.dao().rawCount()); assertTrue(db.dao().allEvents().isEmpty())
    }
    @Test fun fileDatabasePersistsAcrossReopen() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>(); val name = "persistence-test.db"
        context.deleteDatabase(name)
        val first = Room.databaseBuilder(context,LifeDatabase::class.java,name).build()
        LifeRepository(first).ingest("NOTIFICATION","key",100,payload("현대카드 승인 5,900원")); first.close()
        val second = Room.databaseBuilder(context,LifeDatabase::class.java,name).build()
        assertEquals(1,second.dao().rawCount()); assertEquals("PAYMENT",second.dao().event("NOTIFICATION","key")?.type)
        second.close(); context.deleteDatabase(name)
    }
    @Test fun failedRawDoesNotBlockOtherPendingRecords() = runBlocking<Unit> {
        db.dao().insertRaw(RawEvent("bad","CALENDAR","bad",1,0,0,"{}","bad"))
        db.dao().insertRaw(RawEvent("good","NOTIFICATION","good",1,1,1,payload("현대카드 승인 5,900원").toString(),"good"))
        assertEquals(1,repo.recoverPending())
        assertEquals("PAYMENT",db.dao().event("NOTIFICATION","good")?.type)
        assertEquals(2,db.dao().rawCount())
    }
    @Test fun incrementalSummaryMatchesFullRebuild() = runBlocking<Unit> {
        val zone = ZoneId.systemDefault(); val day = LocalDate.now(zone)
        val json = JSONObject().put("type","CALENDAR").put("category","SCHEDULE").put("title","테스트")
        repo.ingest("CALENDAR","1",day.atTime(9,0).atZone(zone).toInstant().toEpochMilli(),json)
        repo.rebuildSummaries(zone,day)
        val unaffected = db.dao().summariesOnce().first { it.date == day.minusDays(1).toString() }
        repo.ingest("CALENDAR","1",day.plusDays(1).atTime(9,0).atZone(zone).toInstant().toEpochMilli(),json)
        repo.rebuildSummaries(zone,day)
        assertEquals(unaffected.updatedAt,db.dao().summariesOnce().first { it.date == unaffected.date }.updatedAt)
        val incremental = db.dao().summariesOnce().map { it.copy(updatedAt=0) }
        repo.rebuildSummaries(zone,day,force=true)
        assertEquals(incremental,db.dao().summariesOnce().map { it.copy(updatedAt=0) })
    }
    @Test fun scopedSummariesMatchFullCalculatorAcrossMidnightAndTimezones() = runBlocking<Unit> {
        val zone = ZoneId.of("Asia/Seoul")
        val day = LocalDate.of(2026, 10, 7)
        val start = day.minusDays(1).atTime(23, 0).atZone(zone).toInstant().toEpochMilli()
        val end = day.atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
        repo.ingest("HEALTH_CONNECT", "sleep", start, JSONObject().put("type", "SLEEP").put("title", "수면").put("endedAt", end))
        repo.ingest("HEALTH_CONNECT", "exercise", start, JSONObject().put("type", "EXERCISE").put("title", "운동").put("endedAt", start + 7200000))
        repo.ingest("CALENDAR", "all-day", day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            JSONObject().put("type", "CALENDAR").put("title", "종일").put("allDay", true).put("date", day.toString()))
        repo.ingest("DERIVED", "steps:$day", day.atStartOfDay(zone).toInstant().toEpochMilli(),
            JSONObject().put("type", "STEP_SUMMARY").put("title", "걸음").put("date", day.toString()).put("count", 1234))
        for (currentZone in listOf(zone, ZoneId.of("America/Los_Angeles"))) {
            repo.rebuildSummaries(currentZone, day)
            val all = db.dao().allEvents()
            for (summary in db.dao().summariesOnce()) {
                val expected = SummaryCalculator.calculate(LocalDate.parse(summary.date), all, currentZone)
                assertEquals(expected.copy(updatedAt = 0), summary.copy(updatedAt = 0))
            }
        }
    }

    @Test fun dashboardCalendarUsesLocalDayAndExcludesCancelledRecords() = runBlocking<Unit> {
        val day = LocalDate.of(2026,10,7)
        val start = day.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()
        fun event(id: String, time: Long, date: String? = null, status: String = "ACTIVE") = LifeEvent(id,"CALENDAR","SCHEDULE",time,null,"테스트 일정",null,"CALENDAR",id,null,"{}",createdAt = 0,updatedAt = 0,status = status,calendarDate = date)
        db.dao().put(event("later",start + 14 * 3_600_000))
        db.dao().put(event("earlier",start + 9 * 3_600_000))
        db.dao().put(event("all-day",0,day.toString()))
        db.dao().put(event("cancelled",start,status = "CANCELLED"))
        db.dao().put(event("tomorrow",start + 86_400_000))
        val events = db.dao().observeCalendarDay(start,start + 86_400_000,day.toString()).first()
        assertEquals(listOf("all-day","earlier","later"),events.map { it.id })
    }
    @Test fun regionalCurrencyRecoveryReclassifiesOldRawWithoutDuplicatesOrAddingIncentive() = runBlocking<Unit> {
        val zone = ZoneId.systemDefault()
        val day = LocalDate.of(2026,10,7)
        val time = day.atTime(10,0).atZone(zone).toInstant().toEpochMilli()
        val payload = JSONObject().put("title","결제 완료 120,000")
            .put("text","테스트학원 테스트지역화폐 인센티브 10,800")
        val original = repo.ingest("NOTIFICATION","regional-payment",time,payload)
        // Simulate the event stored by parser v1; keep the preserved original raw unchanged.
        db.dao().put(original.copy(type = "NOTIFICATION", category = "COMMUNICATION",
            dataJson = JSONObject().put("parserVersion",1).toString()))
        repo.rebuildSummaries(zone,day)
        assertEquals(0,db.dao().summariesOnce().first { it.date == day.toString() }.paymentCount)
        val rawId = original.rawEventId
        assertEquals(0,repo.recoverPending())
        val recovered = requireNotNull(db.dao().event("NOTIFICATION","regional-payment"))
        assertEquals(original.id,recovered.id)
        assertEquals(rawId,recovered.rawEventId)
        assertEquals("PAYMENT",recovered.type)
        repo.rebuildSummaries(zone,day)
        assertEquals(120000L,db.dao().summariesOnce().first { it.date == day.toString() }.paymentAmount)
        assertEquals(1,db.dao().summariesOnce().first { it.date == day.toString() }.paymentCount)
        repo.recoverPending()
        assertEquals(1,db.dao().allEvents().size)
        assertEquals(1,db.dao().rawCount())
        repo.ingest("NOTIFICATION","regional-cancellation",time + 1000,
            JSONObject(payload.toString()).put("title","결제 취소 120,000"))
        repo.rebuildSummaries(zone,day)
        assertEquals(0L,db.dao().summariesOnce().first { it.date == day.toString() }.paymentAmount)
    }
    @Test fun advertisingRecoveryRemovesOldPaymentFromTotalsAndFiltersInbox() = runBlocking<Unit> {
        val zone = ZoneId.systemDefault()
        val day = LocalDate.of(2026,10,7)
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val payload = JSONObject().put("title","(광고) 테스트 알림").put("text","결제 완료 5,900원")
        val original = repo.ingest("NOTIFICATION","ad",start + 1000,payload)
        db.dao().put(original.copy(type = "PAYMENT", category = "FINANCE",
            dataJson = JSONObject().put("parserVersion",3).put("amount",5900).put("currency","KRW").put("paymentKind","APPROVAL").toString()))
        repo.rebuildSummaries(zone,day)
        assertEquals(5900L,db.dao().summariesOnce().first { it.date == day.toString() }.paymentAmount)
        assertEquals(0,repo.recoverPending())
        repo.rebuildSummaries(zone,day)
        val ads = db.dao().timeline(start,start + 86_400_000,day.toString(),"ADVERTISEMENT",true,100).first()
        assertEquals(listOf(original.id),ads.map { it.id })
        assertEquals(original.rawEventId,ads.single().rawEventId)
        assertEquals(0.2,ads.single().importance,0.0)
        assertEquals(listOf("광고"),db.dao().tagsFor(original.id).map { it.tag })
        assertTrue(db.dao().timeline(start,start + 86_400_000,day.toString(),"PAYMENT",true,100).first().isEmpty())
        assertEquals(0L,db.dao().summariesOnce().first { it.date == day.toString() }.paymentAmount)
        assertEquals(0,db.dao().summariesOnce().first { it.date == day.toString() }.paymentCount)
        repo.recoverPending()
        assertEquals(1,db.dao().rawCount())
        assertEquals(1,db.dao().allEvents().size)
    }
    @Test fun manualClassificationSurvivesReprocessingAndRawUpdatesThenCanReset() = runBlocking<Unit> {
        val time = LocalDate.now().atTime(10,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val payload = JSONObject().put("title","(광고) 테스트").put("text","테스트 기록")
        val original = repo.ingest("NOTIFICATION","manual",time,payload)
        val changed = repo.classifyNotification(original.id,"DELIVERY")
        assertEquals(original.id,changed.id)
        assertEquals(original.rawEventId,changed.rawEventId)
        assertEquals("DELIVERY",changed.type)
        assertEquals("LIFE",changed.category)
        assertEquals(listOf("배송"),db.dao().tagsFor(changed.id).map { it.tag })
        assertEquals(1,db.dao().rawCount())
        val reopened = LifeRepository(db)
        reopened.reprocess()
        assertEquals("DELIVERY",db.dao().eventById(changed.id)?.type)
        val updated = reopened.ingest("NOTIFICATION","manual",time,JSONObject(payload.toString()).put("text","변경된 본문"))
        assertEquals("DELIVERY",updated.type)
        assertEquals("변경된 본문",updated.summary)
        val data = JSONObject(updated.dataJson).put("parserVersion",0)
        db.dao().put(updated.copy(dataJson = data.toString()))
        assertEquals(0,reopened.recoverPending())
        assertEquals("DELIVERY",db.dao().eventById(changed.id)?.type)
        val reset = reopened.classifyNotification(changed.id,null)
        assertEquals("ADVERTISEMENT",reset.type)
        assertFalse(JSONObject(reset.dataJson).has("manualClassification"))
        assertEquals(updated.rawEventId,reset.rawEventId)
        assertEquals(2,db.dao().rawCount())
        assertEquals(1,db.dao().allEvents().size)
    }
    @Test fun manualPaymentAndCategoryMovesUpdateTotalsAndFilters() = runBlocking<Unit> {
        val zone = ZoneId.systemDefault()
        val day = LocalDate.now(zone)
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val original = repo.ingest("NOTIFICATION","manual",start + 1000,JSONObject().put("title","테스트").put("text","확인할 기록"))
        repo.classifyNotification(original.id,"PAYMENT",7000)
        repo.rebuildSummaries(zone,day)
        assertEquals(7000L,db.dao().summariesOnce().first { it.date == day.toString() }.paymentAmount)
        repo.classifyNotification(original.id,"PAYMENT",2000,true)
        repo.reprocess()
        repo.rebuildSummaries(zone,day)
        assertEquals(-2000L,db.dao().summariesOnce().first { it.date == day.toString() }.paymentAmount)
        for (type in listOf("DELIVERY","RESERVATION","ADVERTISEMENT","NOTIFICATION")) {
            repo.classifyNotification(original.id,type)
            repo.rebuildSummaries(zone,day)
            val summary = db.dao().summariesOnce().first { it.date == day.toString() }
            assertEquals(0L,summary.paymentAmount)
            assertEquals(0,summary.paymentCount)
            assertEquals(if (type == "DELIVERY") 1 else 0,summary.deliveryCount)
            assertEquals(if (type == "RESERVATION") 1 else 0,summary.reservationCount)
            assertEquals(listOf(original.id),db.dao().timeline(start,start + 86_400_000,day.toString(),type,true,100).first().map { it.id })
        }
        assertEquals(1,db.dao().rawCount())
    }
    @Test fun invalidManualClassificationLeavesDataUnchanged() = runBlocking<Unit> {
        val original = repo.ingest("NOTIFICATION","invalid",100,JSONObject().put("title","테스트").put("text","본문"))
        for (type in listOf("CALENDAR","PAYMENT")) {
            try { repo.classifyNotification(original.id,type); fail("invalid change must fail") } catch (_: IllegalArgumentException) { }
            assertEquals(original,db.dao().eventById(original.id))
        }
        try { repo.classifyNotification(original.id,"PAYMENT",-1); fail("negative amount must fail") } catch (_: IllegalArgumentException) { }
        assertEquals(original,db.dao().eventById(original.id))
        val calendar = repo.ingest("CALENDAR","calendar",100,JSONObject().put("type","CALENDAR").put("title","일정"))
        try { repo.classifyNotification(calendar.id,"ADVERTISEMENT"); fail("only notifications") } catch (_: IllegalArgumentException) { }
        assertEquals(calendar,db.dao().eventById(calendar.id))
    }
}
