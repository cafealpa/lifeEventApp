package com.lifedashboard

import android.app.Application
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
@Config(sdk=[35],application=Application::class)
class SpotTraceIntegrationTest {
    private lateinit var db: LifeDatabase
    private lateinit var repo: LifeRepository
    private val dataset="33aa9900-2998-4488-8222-335566778899"
    private val time=LocalDate.of(2026,10,8).atTime(9,0).atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()
    @Before fun setup() {db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),LifeDatabase::class.java).allowMainThreadQueries().build();repo=LifeRepository(db)}
    @After fun close() {db.close()}
    private fun record(id:Long=1)=SpotRecord(id,"회사",time,"2026-10-08","ENTER","p")
    @Test fun snapshotImportIsIdempotentAndPreservesRawWhenDeleted()=runBlocking<Unit> {
        val snapshot=SpotSnapshot(dataset,listOf(record()))
        repo.importSpotSnapshot(snapshot);val first=repo.dao.activeVisits().single()
        repo.importSpotSnapshot(snapshot);assertEquals(1,repo.dao.rawCount());assertEquals(first.id,repo.dao.activeVisits().single().id)
        repo.importSpotSnapshot(SpotSnapshot(dataset,emptyList()))
        assertEquals("DELETED",repo.dao.eventById(first.id)?.status);assertEquals(2,repo.dao.rawCount())
        repo.reprocess();assertTrue(repo.dao.activeVisits().isEmpty())
        repo.importSpotSnapshot(snapshot);assertEquals(first.id,repo.dao.activeVisits().single().id)
    }
    @Test fun newDatasetNeverOverwritesReusedIdsAndArchivesPreviousFromAggregates()=runBlocking<Unit> {
        repo.importSpotSnapshot(SpotSnapshot(dataset,listOf(record())))
        val first=repo.dao.activeVisits().single()
        repo.importSpotSnapshot(SpotSnapshot("44aa9900-2998-4488-8222-335566778899",listOf(record())))
        assertEquals("UNVERIFIED",repo.dao.eventById(first.id)?.status)
        assertEquals(2,repo.dao.allEvents().size);assertNotEquals(first.id,repo.dao.activeVisits().single().id)
        repo.reprocess();assertEquals(1,repo.dao.activeVisits().size)
    }
    private fun stream(rows:List<SpotRecord>,complete:Boolean=true,count:Int=rows.size):String = buildString {
        appendLine(JSONObject().put("version",1).put("datasetId",dataset).put("snapshotId","s"))
        rows.forEach {appendLine(it.json())}
        if(complete) appendLine(JSONObject().put("complete",true).put("snapshotId","s").put("count",count))
    }
    @Test fun interruptedOrDuplicateSnapshotCannotBeAccepted() {
        assertThrows(IllegalArgumentException::class.java) {SpotSnapshot.read(stream(listOf(record()),false).reader().buffered())}
        assertThrows(IllegalArgumentException::class.java) {SpotSnapshot.read(stream(listOf(record(),record())).reader().buffered())}
        assertThrows(IllegalArgumentException::class.java) {SpotSnapshot.read(stream(listOf(record()),count=2).reader().buffered())}
        assertEquals(listOf(record()),SpotSnapshot.read(stream(listOf(record())).reader().buffered()).records)
    }
    @Test fun rejectedSnapshotLeavesExistingLifeEventsUntouched()=runBlocking<Unit> {
        repo.importSpotSnapshot(SpotSnapshot(dataset,listOf(record())))
        try { repo.importSpotSnapshot(SpotSnapshot.read(stream(emptyList(),false).reader().buffered()));fail() } catch (_:IllegalArgumentException) {}
        assertEquals(1,repo.dao.activeVisits().size)
    }
    @Test fun sameAmountMerchantChangeIsVisibleWithoutDailySummaryInvalidation()=runBlocking<Unit> {
        fun payload(merchant:String)=JSONObject().put("type","PAYMENT").put("category","FINANCE").put("title",merchant).put("merchant",merchant).put("amount",5000).put("currency","KRW").put("paymentKind","APPROVAL")
        repo.ingest("TEST","1",time,payload("카페"))
        val q=CardQuery(metric=CardMetric.SUM,merchant="카페")
        val service=DashboardQueryRepository(repo.dao)
        assertEquals("5,000원",service.observe(q,LocalDate.of(2026,10,8),ZoneId.of("Asia/Seoul")).first().value)
        val before=repo.dao.event("TEST","1")!!
        val after=repo.ingest("TEST","1",time,payload("식당"))
        assertFalse(changesSummary(before,after))
        assertEquals("0원",service.observe(q,LocalDate.of(2026,10,8),ZoneId.of("Asia/Seoul")).first().value)
    }
    @Test fun cardRemovalAndReloadNeverRemoveLifeData()=runBlocking<Unit> {
        repo.importSpotSnapshot(SpotSnapshot(dataset,listOf(record())))
        val context=ApplicationProvider.getApplicationContext<Application>()
        val store=DashboardCardStore(context)
        store.save(emptyList())
        assertTrue(DashboardCardStore(context).cards.value.isEmpty());assertEquals(1,repo.dao.activeVisits().size)
        store.save(DashboardCardStore.defaults())
    }
}
