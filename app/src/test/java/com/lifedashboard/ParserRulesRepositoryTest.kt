package com.lifedashboard

import android.app.Application
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ParserRulesRepositoryTest {
    private lateinit var db: LifeDatabase
    private lateinit var store: ParserRuleStore
    private lateinit var repo: LifeRepository
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Before fun setup() {
        context.getSharedPreferences("notification_parser_rules", 0).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, LifeDatabase::class.java).allowMainThreadQueries().build()
        store = ParserRuleStore(context)
        repo = LifeRepository(db) { store.state.value }
    }
    @After fun close() { db.close() }
    private val rule = ParserRule(name = "쇼핑 결제", phrase = "주문 접수", type = "PAYMENT")
    private fun payload() = JSONObject().put("text", "주문 접수\n결제금액 12,300원").put("title", "쇼핑 알림")

    @Test fun savedRulesRecoverExistingRawAndRemovalRestoresDefault() = runBlocking {
        val event = repo.ingest("NOTIFICATION", "key", 1000, payload())
        assertEquals("NOTIFICATION", event.type)
        store.save(listOf(rule))
        assertEquals(listOf(rule), ParserRuleStore(context).state.value.rules)
        repo.recoverPending()
        var updated = requireNotNull(db.dao().eventById(event.id))
        assertEquals("PAYMENT", updated.type)
        assertEquals(event.rawEventId, updated.rawEventId)
        assertEquals(1, db.dao().rawCount())
        store.save(emptyList())
        updated = repo.ingest("NOTIFICATION", "key", 1000, payload())
        assertEquals("NOTIFICATION", updated.type)
        assertEquals(event.id, updated.id)
        assertEquals(1, db.dao().rawCount())
    }
    @Test fun manualOverrideSurvivesRulesAndResetUsesCurrentRules() = runBlocking {
        val event = repo.ingest("NOTIFICATION", "key", 1000, payload())
        repo.classifyNotification(event.id, "DELIVERY")
        store.save(listOf(rule))
        repo.reprocess()
        assertEquals("DELIVERY", db.dao().eventById(event.id)!!.type)
        val reset = repo.classifyNotification(event.id, null)
        assertEquals("PAYMENT", reset.type)
        assertEquals(12300, JSONObject(reset.dataJson).getLong("amount"))
    }
}
