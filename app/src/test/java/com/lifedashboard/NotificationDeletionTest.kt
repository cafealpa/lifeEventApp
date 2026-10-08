package com.lifedashboard

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NotificationDeletionTest {
    private lateinit var db: LifeDatabase
    private lateinit var repo: LifeRepository
    private val zone = ZoneId.systemDefault()
    private val day = LocalDate.of(2026,10,8)
    private val time = day.atTime(10,0).atZone(zone).toInstant().toEpochMilli()
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LifeDatabase::class.java).allowMainThreadQueries().build()
        repo = LifeRepository(db)
    }
    @After fun close() { db.close() }
    private suspend fun notification(key: String, text: String) = repo.ingest("NOTIFICATION",key,time,JSONObject().put("title","알림").put("text",text))

    @Test fun deletionRemovesAllRevisionsAndRelationsAndCannotReappearOnReanalysis() = runBlocking {
        val event = notification("one","결제 완료 1000원")
        notification("one","결제 완료 2000원")
        db.dao().tags(listOf(EventTag(event.id,"테스트")))
        db.dao().entities(listOf(EventEntity("entity",event.id,"COMPANY","테스트","테스트")))
        val other = notification("two","결제 완료 3000원")
        repo.rebuildSummaries(zone,day)
        repo.deleteNotification(event.id)
        assertNull(db.dao().eventById(event.id))
        assertNull(db.dao().latestRaw("NOTIFICATION","one"))
        assertTrue(db.dao().tagsFor(event.id).isEmpty())
        assertTrue(db.dao().entitiesFor(event.id).isEmpty())
        assertEquals(1,db.dao().rawCount())
        assertTrue(repo.needsDerivation(zone,day))
        repo.reprocess(); repo.recoverPending(); repo.rebuildSummaries(zone,day)
        assertEquals(other.id,db.dao().timeline(0,Long.MAX_VALUE,day.toString(),"",false,100).first().single().id)
        val summary = db.dao().summariesOnce().first { it.date == day.toString() }
        assertEquals(3000L,summary.paymentAmount)
        assertEquals(1,summary.paymentCount)
        repo.deleteNotification(event.id) // Repeated deletion is harmless.
    }
    @Test fun cancellingPaymentDeletionRestoresNetTotal() = runBlocking {
        notification("approval","결제 완료 3000원")
        val cancellation = notification("cancel","결제 취소 1000원")
        repo.rebuildSummaries(zone,day)
        assertEquals(2000L,db.dao().summariesOnce().first { it.date == day.toString() }.paymentAmount)
        repo.deleteNotification(cancellation.id); repo.rebuildSummaries(zone,day)
        assertEquals(3000L,db.dao().summariesOnce().first { it.date == day.toString() }.paymentAmount)
    }
    @Test fun everyManualCategoryCanBeDeleted() = runBlocking {
        for (type in notificationClassifications.keys) {
            val event = notification(type,"일반 안내")
            repo.classifyNotification(event.id,type,if(type == "PAYMENT") 1000L else null)
            repo.deleteNotification(event.id)
            assertNull(db.dao().eventById(event.id))
            assertNull(db.dao().latestRaw("NOTIFICATION",type))
        }
        assertEquals(0,db.dao().rawCount())
    }
    @Test fun calendarDeletionIsRejectedWithoutChangingData() = runBlocking {
        val event = repo.ingest("CALENDAR","calendar",time,JSONObject().put("type","CALENDAR").put("title","테스트 일정").put("endedAt",time+3600000))
        try { repo.deleteNotification(event.id); fail("Calendar must be protected") } catch (_: IllegalArgumentException) { }
        assertNotNull(db.dao().eventById(event.id))
        assertEquals(1,db.dao().rawCount())
    }
}
