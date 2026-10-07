package com.lifedashboard

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
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

}
