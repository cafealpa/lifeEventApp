package com.lifedashboard

import android.app.Activity
import android.app.Application
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ThemePreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Before fun resetAppearance() {
        context.getSharedPreferences("appearance", 0).edit().clear().commit()
    }

    @Test fun savedChoiceSurvivesNewStoreAndInvalidChoiceUsesDefault() {
        assertEquals(AppTheme.GRAPHITE, ThemePreferences(context).selected())
        AppTheme.entries.forEach { theme ->
            ThemePreferences(context).select(theme)
            assertEquals(theme, ThemePreferences(context).selected())
        }
        context.getSharedPreferences("appearance", 0).edit().putString("theme", "removed-theme").commit()
        assertEquals(AppTheme.GRAPHITE, ThemePreferences(context).selected())
    }

    @Test fun themeTextIsReadableOnItsActualSurfaces() {
        AppTheme.entries.forEach { theme ->
            val c = theme.colorScheme()
            listOf(c.onBackground to c.background, c.onSurface to c.surface,
                c.onSurfaceVariant to c.surface, c.onSurfaceVariant to c.primaryContainer,
                c.onPrimary to c.primary, c.onPrimaryContainer to c.primaryContainer).forEach { (text, surface) ->
                val contrast = ColorUtils.calculateContrast(text.toArgb(), surface.toArgb())
                assertTrue("${theme.title}: text contrast $contrast", contrast >= 4.5)
            }
        }
    }

    @Test fun activityStartupUsesSavedSystemBarAppearance() {
        AppTheme.entries.forEach { theme ->
            ThemePreferences(context).select(theme)
            val controller = Robolectric.buildActivity(Activity::class.java).setup()
            val activity = controller.get()
            activity.prepareLifeTheme()
            val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            assertEquals(!theme.dark, bars.isAppearanceLightStatusBars)
            assertEquals(!theme.dark, bars.isAppearanceLightNavigationBars)
            controller.pause().stop().destroy()
        }
    }
}
