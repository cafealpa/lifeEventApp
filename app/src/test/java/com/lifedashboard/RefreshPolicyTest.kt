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
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RefreshPolicyTest {
    private lateinit var db: LifeDatabase
    private lateinit var repo: LifeRepository
    private val zone = ZoneId.systemDefault()
    private val day = LocalDate.of(2026,10,8)
    private val time = day.atTime(10,0).atZone(zone).toInstant().toEpochMilli()
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LifeDatabase::class.java).allowMainThreadQueries().build(); repo = LifeRepository(db) }
    @After fun close() { db.close() }
    private fun payload(text: String) = JSONObject().put("title", "알림").put("text", text)
    private suspend fun derive() { repo.rebuildSummaries(zone,day); repo.generateBriefing(day,zone,time) }
    @Test fun unrelatedNotificationsAndMetadataDoNotInvalidateButPaymentAmountDoes() = runBlocking {
        derive()
        repo.ingest("NOTIFICATION","ad",time,payload("(광고) 안내"))
        repo.ingest("NOTIFICATION","other",time,payload("일반 안내"))
        assertFalse(repo.needsDerivation(zone,day,time))
        repo.ingest("NOTIFICATION","payment",time,payload("결제 완료 1000원"))
        assertTrue(repo.needsDerivation(zone,day,time))
        derive()
        repo.ingest("NOTIFICATION","payment",time,payload("결제 완료 1000원\n추가 안내"))
        assertFalse(repo.needsDerivation(zone,day,time))
        repo.ingest("NOTIFICATION","payment",time,payload("결제 완료 2000원"))
        assertTrue(repo.needsDerivation(zone,day,time))
        derive()
        assertEquals(2000,db.dao().summariesOnce().first { it.date == day.toString() }.paymentAmount)
    }
    @Test fun reclassificationRemovesOldContribution() = runBlocking {
        val event=repo.ingest("NOTIFICATION","payment",time,payload("결제 완료 1000원"))
        derive()
        repo.classifyNotification(event.id,"ADVERTISEMENT")
        assertTrue(repo.needsDerivation(zone,day,time))
        derive()
        assertEquals(0,db.dao().summariesOnce().first { it.date == day.toString() }.paymentCount)
        assertFalse(repo.needsDerivation(zone,day,time))
    }
    @Test fun missingBriefingNewDayAndTimezoneStillNeedDerivation() = runBlocking {
        assertTrue(repo.needsDerivation(zone,day,time))
        repo.rebuildSummaries(zone,day)
        assertTrue(repo.needsDerivation(zone,day,time))
        repo.generateBriefing(day,zone,time)
        assertFalse(repo.needsDerivation(zone,day,time))
        assertTrue(repo.needsDerivation(zone,day.plusDays(1)))
        assertTrue(repo.needsDerivation(ZoneId.of(if(zone.id == "UTC") "Asia/Seoul" else "UTC"),day))
    }
    @Test fun resumeGateOnlySkipsRecentSuccessfulSameDayCollection() {
        val gate=ResumeRefreshGate()
        assertTrue(gate.shouldRefresh(1000,"2026-10-08","Asia/Seoul"))
        gate.completed(1000,"2026-10-08","Asia/Seoul")
        assertFalse(gate.shouldRefresh(300999,"2026-10-08","Asia/Seoul"))
        assertTrue(gate.shouldRefresh(301000,"2026-10-08","Asia/Seoul"))
        assertTrue(gate.shouldRefresh(2000,"2026-10-09","Asia/Seoul"))
        assertTrue(gate.shouldRefresh(2000,"2026-10-08","UTC"))
        gate.reset()
        assertTrue(gate.shouldRefresh(2000,"2026-10-08","Asia/Seoul"))
    }
}
