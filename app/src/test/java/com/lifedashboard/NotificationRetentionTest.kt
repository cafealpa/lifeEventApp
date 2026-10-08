package com.lifedashboard

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NotificationRetentionTest {
    private lateinit var db: LifeDatabase
    private lateinit var repo: LifeRepository
    private val now = 1800000000000L
    private fun days(value: Long) = Duration.ofDays(value).toMillis()
    private fun payload(text: String) = JSONObject().put("title", "테스트 알림").put("text", text)
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LifeDatabase::class.java).allowMainThreadQueries().build(); repo = LifeRepository(db) }
    @After fun close() { db.close() }

    @Test fun exactAgeBoundaryUsesOccurrenceNotProcessingTime() = runBlocking {
        repo.ingest("NOTIFICATION", "ad-expired", now - days(14), payload("(광고) 안내"))
        val ad = repo.ingest("NOTIFICATION", "ad-young", now - days(14) + 1, payload("(광고) 안내"))
        repo.ingest("NOTIFICATION", "other-expired", now - days(30), payload("안내"))
        val other = repo.ingest("NOTIFICATION", "other-young", now - days(30) + 1, payload("안내"))
        assertEquals(2, repo.purgeExpiredNotifications(now))
        assertEquals(setOf(ad.id, other.id), db.dao().allEvents().map { it.id }.toSet())
        assertEquals(2, db.dao().rawCount())
        assertEquals(0, repo.purgeExpiredNotifications(now))
    }
    @Test fun rawRevisionsTagsAndEntitiesAreRemovedAndReprocessingDoesNotRestore() = runBlocking {
        val event = repo.ingest("NOTIFICATION", "old", now - days(40), payload("(광고) 첫 내용"))
        repo.ingest("NOTIFICATION", "old", now - days(40), payload("(광고) 수정 내용"))
        db.dao().entities(listOf(EventEntity("entity", event.id, "COMPANY", "테스트", "테스트")))
        assertEquals(2, db.dao().rawCount())
        assertEquals(1, repo.purgeExpiredNotifications(now))
        assertEquals(0, db.dao().rawCount())
        assertTrue(db.dao().tagsFor(event.id).isEmpty())
        assertTrue(db.dao().entitiesFor(event.id).isEmpty())
        repo.reprocess(); repo.recoverPending()
        assertTrue(db.dao().allEvents().isEmpty())
    }
    @Test fun currentManualCategoryControlsRetentionAndOtherSourcesArePreserved() = runBlocking {
        val kept = repo.ingest("NOTIFICATION", "keep", now - days(60), payload("(광고) 안내"))
        repo.classifyNotification(kept.id, "PAYMENT", 1000)
        val removed = repo.ingest("NOTIFICATION", "remove", now - days(60), payload("결제 완료 1000원"))
        repo.classifyNotification(removed.id, "NOTIFICATION")
        val delivery = repo.ingest("NOTIFICATION", "delivery", now - days(60), payload("배송 완료"))
        val external = repo.ingest("CALENDAR", "external", now - days(60), JSONObject().put("type", "NOTIFICATION").put("title", "보존"))
        assertEquals(1, repo.purgeExpiredNotifications(now))
        assertEquals(setOf(kept.id, delivery.id, external.id), db.dao().allEvents().map { it.id }.toSet())
        assertNotNull(db.dao().latestRaw("NOTIFICATION", "keep"))
        assertNull(db.dao().latestRaw("NOTIFICATION", "remove"))
    }
}
