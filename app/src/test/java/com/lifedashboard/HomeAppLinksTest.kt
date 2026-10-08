package com.lifedashboard

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HomeAppLinksTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val links = HomeAppLinks(context)
    @Before fun reset() { context.getSharedPreferences("home_app_links", 0).edit().clear().commit() }

    @Test fun selectionsPersistIndependentlyAndCanBeRemoved() {
        links.select("CALENDAR", "example.calendar")
        links.select("SLEEP", "example.health")
        links.select("EXERCISE", "example.health")
        val restored = HomeAppLinks(context)
        assertEquals("example.calendar", restored.selected("CALENDAR"))
        assertEquals("example.health", restored.selected("SLEEP"))
        assertNull(restored.selected("STEP_SUMMARY"))
        restored.select("SLEEP", null)
        assertNull(links.selected("SLEEP"))
        assertEquals("example.health", links.selected("EXERCISE"))
    }

    @Test fun missingAndUninstalledAppsReturnWithoutStartingActivity() {
        assertFalse(links.open("SLEEP"))
        links.select("SLEEP", "example.missing")
        assertFalse(links.open("SLEEP"))
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test fun selectedAppOpensLauncherWithoutRecordDataOrDeepLink() {
        val packageName = "example.health"
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = "$packageName.MainActivity"
                exported = true
                applicationInfo = ApplicationInfo().apply { this.packageName = packageName }
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(launcher, info)
        links.select("SLEEP", packageName)
        assertTrue(links.open("SLEEP"))
        val started = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_MAIN, started.action)
        assertEquals(packageName, started.component?.packageName)
        assertTrue(started.hasCategory(Intent.CATEGORY_LAUNCHER))
        assertNull(started.data)
        assertNull(started.extras)
    }
}
