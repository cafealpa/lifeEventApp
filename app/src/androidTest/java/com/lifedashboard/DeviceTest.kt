package com.lifedashboard

import android.Manifest
import android.content.ContentValues
import android.content.ContentUris
import android.content.Intent
import android.provider.CalendarContract
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.*

@RunWith(AndroidJUnit4::class)
class DeviceTest {
    @Test fun nativeDatabaseReplayAndTimeline() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LifeDatabase::class.java).build()
        try {
            val repo = LifeRepository(db)
            val time = System.currentTimeMillis()
            val payload = JSONObject().put("title","테스트 승인").put("text","현대카드 승인 5,900원\n가맹점: 테스트카페")
            val e = repo.ingest("NOTIFICATION","device-test",time,payload)
            repo.ingest("NOTIFICATION","device-test",time,payload); repo.reprocess()
            assertEquals(1,db.dao().rawCount()); assertEquals("PAYMENT",db.dao().allEvents().single().type)
            assertEquals(e.id,db.dao().timeline(0,Long.MAX_VALUE,"", "",false,100).first().single().id)
            repo.rebuildSummaries()
            assertNull(db.dao().event("DERIVED","briefing:${LocalDate.now()}"))
        } finally { db.close() }
    }

    @Test fun calendarProviderRoundTrip() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.READ_CALENDAR").close()
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val resolver = context.contentResolver
        val db = Room.inMemoryDatabaseBuilder(context,LifeDatabase::class.java).build()
        var calendarId: Long? = null
        try {
            val calendarUri = CalendarContract.Calendars.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER,"true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME,"life-test").appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE,"LOCAL").build()
            val values = ContentValues().apply {
                put(CalendarContract.Calendars.ACCOUNT_NAME,"life-test"); put(CalendarContract.Calendars.ACCOUNT_TYPE,"LOCAL")
                put(CalendarContract.Calendars.NAME,"Life Test"); put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,"Life Test")
                put(CalendarContract.Calendars.CALENDAR_COLOR,0x336699); put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,CalendarContract.Calendars.CAL_ACCESS_OWNER)
                put(CalendarContract.Calendars.OWNER_ACCOUNT,"life-test"); put(CalendarContract.Calendars.SYNC_EVENTS,1); put(CalendarContract.Calendars.VISIBLE,1)
                put(CalendarContract.Calendars.CALENDAR_TIME_ZONE,ZoneId.systemDefault().id)
            }
            calendarId = ContentUris.parseId(requireNotNull(resolver.insert(calendarUri,values)))
            val begin = LocalDate.now().atTime(14,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val eventValues = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID,calendarId); put(CalendarContract.Events.TITLE,"검증 일정")
                put(CalendarContract.Events.DTSTART,begin); put(CalendarContract.Events.DTEND,begin+3_600_000)
                put(CalendarContract.Events.EVENT_TIMEZONE,ZoneId.systemDefault().id)
            }
            val eventUri = requireNotNull(resolver.insert(CalendarContract.Events.CONTENT_URI,eventValues))
            val eventId = ContentUris.parseId(eventUri).toString()
            val repo = LifeRepository(db); val collector = CalendarCollector(context,repo)
            collector.collect(); collector.collect()
            assertEquals("검증 일정",db.dao().event("CALENDAR",eventId)?.title)
            val original = requireNotNull(db.dao().event("CALENDAR",eventId))
            resolver.update(eventUri,ContentValues().apply { put(CalendarContract.Events.TITLE,"수정 일정"); put(CalendarContract.Events.DTSTART,begin+86_400_000); put(CalendarContract.Events.DTEND,begin+90_000_000) },null,null)
            collector.collect()
            assertEquals(original.id,db.dao().event("CALENDAR",eventId)?.id)
            assertEquals("수정 일정",db.dao().event("CALENDAR",eventId)?.title)
            resolver.delete(eventUri,null,null); collector.collect()
            assertEquals("DELETED",db.dao().event("CALENDAR",eventId)?.status)
        } finally {
            calendarId?.let { resolver.delete(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI,it).buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER,"true").appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME,"life-test").appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE,"LOCAL").build(),null,null) }
            db.close(); instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test fun mainActivityLaunches() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        assertFalse(activity.isFinishing)
        instrumentation.runOnMainSync { activity.finish() }
    }

    @Test fun notificationListenerReceivesUpdatesWithoutDuplicatingEvent() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val graph = (context.applicationContext as LifeApplication).graph
        val device = UiDevice.getInstance(instrumentation)
        graph.status.enable(true)
        try {
            device.executeShellCommand("cmd notification allow_listener ${context.packageName}/.LifeNotificationListener")
            val marker = "life-test-${System.nanoTime()}"
            device.executeShellCommand("cmd notification post -t Test $marker ${marker}_1")
            val first = withTimeout(15000) {
                var found: LifeEvent? = null
                while (found == null) { found = graph.repository.dao.allEvents().firstOrNull { it.sourceType == "NOTIFICATION" && it.summary == "${marker}_1" }; if (found == null) delay(200) }
                found
            }
            device.executeShellCommand("cmd notification post -t Test $marker ${marker}_2")
            withTimeout(15000) { while (graph.repository.dao.eventById(first.id)?.summary != "${marker}_2") delay(200) }
            assertEquals(1,graph.repository.dao.allEvents().count { it.sourceId == first.sourceId })
        } finally {
            graph.status.enable(false)
            device.executeShellCommand("cmd notification disallow_listener ${context.packageName}/.LifeNotificationListener")
            // This test runs only against the isolated test AVD app installation.
            graph.clear()
        }
    }

    @Test fun basicNavigationAndPrivacyControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            assertTrue(device.wait(Until.hasObject(By.text("오늘 한눈에")),10000))
            device.findObject(By.desc("설정")).click()
            assertTrue(device.wait(Until.hasObject(By.text("생활 데이터 이용 안내")),5000))
            assertNotNull(device.findObject(By.text("동의하고 수집 시작")))
            device.findObject(By.text("타임라인")).click()
            assertTrue(device.wait(Until.hasObject(By.desc("이전 날짜")),5000))
            device.findObject(By.text("홈")).click()
            repeat(6) {
                if (!device.hasObject(By.text("알림 보관함"))) device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 3, 20)
            }
            assertTrue(device.wait(Until.hasObject(By.text("알림 보관함")),5000))
            device.findObject(By.text("알림 보관함")).click()
            assertTrue(device.wait(Until.hasObject(By.text("이 날짜에 저장된 기록이 없어요.")),5000))
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun healthConnectPermissionAndReadRoundTrip() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        listOf("READ_STEPS","READ_SLEEP","READ_EXERCISE").forEach { permission ->
            device.executeShellCommand("pm grant ${context.packageName} android.permission.health.$permission")
        }
        val db = Room.inMemoryDatabaseBuilder(context,LifeDatabase::class.java).build()
        try {
            val collector = HealthCollector(context,LifeRepository(db))
            assertEquals(androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE,collector.availability())
            assertTrue(collector.client().permissionController.getGrantedPermissions().containsAll(HealthCollector.permissions))
            collector.collect(background=false)
            assertEquals(30,db.dao().allEvents().count { it.type == "STEP_SUMMARY" })
            collector.collect(background=false)
            assertEquals(30,db.dao().allEvents().count { it.type == "STEP_SUMMARY" })
        } finally { db.close() }
    }
}
