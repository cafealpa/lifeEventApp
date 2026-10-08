package com.lifedashboard

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TimelineSortTest {
    private lateinit var db: LifeDatabase
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LifeDatabase::class.java).allowMainThreadQueries().build() }
    @After fun close() { db.close() }
    private fun event(id: String, time: Long, type: String = "NOTIFICATION", end: Long? = null) = LifeEvent(id, type, "LIFE", time, end, id, "", "NOTIFICATION", id, null, "{}", createdAt = 0, updatedAt = 0)
    @Test fun sortIsAppliedBeforeLimitAndMoreKeepsOrder() = runBlocking {
        (1..105).forEach { db.dao().put(event("id-$it", it.toLong())) }
        suspend fun query(limit: Int, oldest: Boolean) = db.dao().timeline(0, 1000, "2026-10-08", "", false, limit, oldest).first().map { it.occurredAt }
        assertEquals((105L downTo 6L).toList(), query(100, false))
        assertEquals((1L..100L).toList(), query(100, true))
        assertEquals((1L..105L).toList(), query(200, true))
    }
    @Test fun sleepUsesEndTimeAndEqualTimesHaveStableReverseOrdering() = runBlocking {
        db.dao().put(event("a", 20))
        db.dao().put(event("b", 20))
        db.dao().put(event("sleep", 1, "SLEEP", 30))
        suspend fun query(oldest: Boolean) = db.dao().timeline(0, 100, "2026-10-08", "", false, 100, oldest).first().map { it.id }
        assertEquals(listOf("a", "b", "sleep"), query(true))
        assertEquals(listOf("sleep", "b", "a"), query(false))
        assertEquals(listOf("sleep"), db.dao().timeline(0,100,"2026-10-08","SLEEP",false,100,true).first().map { it.id })
    }
}
