package com.lifedashboard

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
class CalendarDuplicatesTest {
    private lateinit var db: LifeDatabase
    private lateinit var repo: LifeRepository
    private val zone = ZoneId.systemDefault()
    private val day = LocalDate.of(2026, 10, 8)
    private val start = day.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LifeDatabase::class.java).allowMainThreadQueries().build(); repo = LifeRepository(db) }
    @After fun close() { db.close() }
    private fun payload(title: String = "팀 회의", place: String = "회의실", end: Long = start + 3600000) = JSONObject()
        .put("type", "CALENDAR").put("category", "SCHEDULE").put("title", title).put("summary", place).put("allDay", false).put("endedAt", end)
    private suspend fun visible() = db.dao().timeline(day.atStartOfDay(zone).toInstant().toEpochMilli(), day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), day.toString(), "CALENDAR", false, 100).first()

    @Test fun duplicatesKeepRawButOnlyOneTimelineSummaryAndBriefingEntry() = runBlocking {
        val first = repo.ingest("CALENDAR", "1", start, payload())
        repo.ingest("CALENDAR", "2", start, payload("  팀   회의  "))
        repo.ingest("CALENDAR", "2", start, payload("  팀   회의  "))
        assertEquals(2, db.dao().rawCount())
        assertEquals(listOf(first.id), visible().map { it.id })
        repo.rebuildSummaries(zone, day)
        assertEquals(1, db.dao().summariesOnce().first { it.date == day.toString() }.calendarCount)
        repo.generateBriefing(day, zone)
        assertTrue(db.dao().briefing().first()!!.summary!!.contains("일정: 1개 (오전 1개)"))
        repo.reprocess()
        assertEquals(1, visible().size)
        assertEquals(2, db.dao().rawCount())
    }

    @Test fun representativeDeletionOrEditRestoresOtherSourceAndDuplicateDeletionIsTracked() = runBlocking {
        val first = repo.ingest("CALENDAR", "1", start, payload())
        repo.ingest("CALENDAR", "2", start, payload())
        assertEquals(2, db.dao().sourceWindow("CALENDAR", start, start + 1).size)
        repo.markDeleted(first)
        assertEquals("2", visible().single().sourceId)
        repo.ingest("CALENDAR", "1", start, payload())
        repo.ingest("CALENDAR", "1", start, payload("다른 회의"))
        assertEquals(2, visible().size)
        repo.markDeleted(requireNotNull(db.dao().event("CALENDAR", "2")))
        assertEquals("1", visible().single().sourceId)
    }

    @Test fun differentPlacesAndTimesStaySeparateAndBlankPlaceDoesNotBridge() = runBlocking {
        repo.ingest("CALENDAR", "1", start, payload(place = ""))
        repo.ingest("CALENDAR", "2", start, payload(place = "회의실 A"))
        repo.ingest("CALENDAR", "3", start, payload(place = "회의실 B"))
        assertEquals(2, visible().size)
        repo.ingest("CALENDAR", "4", start, payload(end = start + 7200000))
        repo.ingest("CALENDAR", "5", start + 60000, payload())
        assertEquals(4, visible().size)
        repo.ingest("CALENDAR", "6:tomorrow", start + 86400000, payload(end = start + 90000000))
        assertEquals(5, db.dao().calendarDuplicateCandidates().count { it.status == "ACTIVE" })
    }

    @Test fun allDayDatesAndLegacyDuplicatesAreReconciledWithoutRawRewrite() = runBlocking {
        val midnight = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val json = payload(end = midnight + 86400000).put("allDay", true).put("date", day.toString())
        val one = repo.ingest("CALENDAR", "1", midnight, json)
        val two = repo.ingest("CALENDAR", "2", midnight, json)
        db.dao().put(two.copy(status = "ACTIVE")) // Simulate records saved by the old app.
        assertEquals(2, visible().size)
        LifeRepository(db).recoverPending()
        assertEquals(listOf(one.id), visible().map { it.id })
        assertEquals(2, db.dao().rawCount())
        repo.ingest("CALENDAR", "3", midnight, JSONObject(json.toString()).put("endedAt", midnight + 172800000))
        repo.ingest("CALENDAR", "4", midnight, JSONObject(json.toString()).put("allDay", false).also { it.remove("date") })
        assertEquals(3, visible().size)
    }
}
