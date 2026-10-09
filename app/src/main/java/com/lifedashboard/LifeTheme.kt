package com.lifedashboard

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

enum class AppTheme(val id: String, val title: String, val description: String, val dark: Boolean = false) {
    GRAPHITE("graphite", "그래파이트", "절제된 흑백"),
    BLUE("blue", "클린 블루", "또렷하고 깔끔한 블루"),
    SAGE("sage", "세이지 그린", "차분한 자연의 녹색"),
    SAND("sand", "웜 샌드", "따뜻하고 부드러운 샌드"),
    LAVENDER("lavender", "소프트 라벤더", "은은한 라벤더"),
    MIDNIGHT("midnight", "미드나이트", "깊고 차분한 다크 네이비", dark = true);

    companion object {
        fun fromId(id: String?): AppTheme = entries.firstOrNull { it.id == id } ?: GRAPHITE
    }
}

class ThemePreferences(context: Context) {
    internal val preferences: SharedPreferences = context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    fun selected(): AppTheme = AppTheme.fromId(preferences.getString("theme", null))
    fun select(theme: AppTheme) { preferences.edit().putString("theme", theme.id).apply() }
}

fun AppTheme.colorScheme(): ColorScheme {
    // Background, card, text, secondary text, divider, accent, accent container.
    val colors = when (this) {
        AppTheme.GRAPHITE -> listOf(0xFFF5F5F5, 0xFFFFFFFF, 0xFF22252A, 0xFF63666D, 0xFFE5E5E7, 0xFF30343B, 0xFFE9EAEC)
        AppTheme.BLUE -> listOf(0xFFF3F6FB, 0xFFFFFFFF, 0xFF1E2B40, 0xFF5C6B80, 0xFFDFE6F0, 0xFF285CC4, 0xFFE6EEFD)
        AppTheme.SAGE -> listOf(0xFFF3F5F0, 0xFFFCFDF9, 0xFF29382D, 0xFF5C685A, 0xFFDFE5DA, 0xFF4C6948, 0xFFE4EBDD)
        AppTheme.SAND -> listOf(0xFFF7F3ED, 0xFFFFFCF7, 0xFF392F29, 0xFF6E6053, 0xFFE8E0D4, 0xFF8C5B3D, 0xFFEFE1D1)
        AppTheme.LAVENDER -> listOf(0xFFF6F4FA, 0xFFFEFDFF, 0xFF312D40, 0xFF6C6279, 0xFFE6E0EF, 0xFF7051A0, 0xFFECE5F6)
        AppTheme.MIDNIGHT -> listOf(0xFF111923, 0xFF1C2735, 0xFFEFF3F8, 0xFFAFBDCD, 0xFF2D3C4F, 0xFF9EBFFF, 0xFF2A3B56)
    }.map { Color(it) }
    val (background, surface, text, muted, line) = colors
    val accent = colors[5]
    val soft = colors[6]
    val onAccent = if (dark) background else Color.White
    val onContainer = if (this == AppTheme.SAND) Color(0xFF87573A) else accent
    return (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = accent, onPrimary = onAccent, primaryContainer = soft, onPrimaryContainer = onContainer,
        secondary = accent, onSecondary = onAccent, secondaryContainer = soft, onSecondaryContainer = onContainer,
        tertiary = accent, onTertiary = onAccent, tertiaryContainer = soft, onTertiaryContainer = onContainer,
        background = background, onBackground = text, surface = surface, onSurface = text,
        surfaceVariant = soft, onSurfaceVariant = muted, surfaceTint = accent,
        outline = muted, outlineVariant = line,
        surfaceDim = background, surfaceBright = surface,
        surfaceContainerLowest = background, surfaceContainerLow = surface,
        surfaceContainer = surface, surfaceContainerHigh = surface, surfaceContainerHighest = soft,
        inverseSurface = text, inverseOnSurface = background, inversePrimary = if (dark) Color(0xFF285CC4) else soft
    )
}

val LocalAppTheme = staticCompositionLocalOf { AppTheme.GRAPHITE }
private val LocalSelectTheme = staticCompositionLocalOf<(AppTheme) -> Unit> { {} }

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Suppress("DEPRECATION")
private fun Activity.applyThemeWindow(theme: AppTheme) {
    val background = theme.colorScheme().background.toArgb()
    window.setBackgroundDrawable(ColorDrawable(background))
    window.statusBarColor = android.graphics.Color.TRANSPARENT
    window.navigationBarColor = background
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !theme.dark
        isAppearanceLightNavigationBars = !theme.dark
    }
}

fun Activity.prepareLifeTheme() {
    val selected = ThemePreferences(this).selected()
    setTheme(if (selected.dark) R.style.Theme_LifeDashboard_Dark else R.style.Theme_LifeDashboard)
    applyThemeWindow(selected)
}

@Composable
fun LifeTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { ThemePreferences(context) }
    var selected by remember(store) { mutableStateOf(store.selected()) }
    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> selected = store.selected() }
        store.preferences.registerOnSharedPreferenceChangeListener(listener)
        selected = store.selected()
        onDispose { store.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    SideEffect { context.activity()?.applyThemeWindow(selected) }
    val scheme = remember(selected) { selected.colorScheme() }
    CompositionLocalProvider(LocalAppTheme provides selected, LocalSelectTheme provides { theme ->
        store.select(theme)
        selected = theme
    }) {
        MaterialTheme(colorScheme = scheme,
            shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp))) {
            // Paint system-bar insets as well as screen content, including secondary screens.
            Surface(Modifier.fillMaxSize(), color = scheme.background, contentColor = scheme.onBackground) { content() }
        }
    }
}

@Composable
internal fun themeSelection(): (AppTheme) -> Unit = LocalSelectTheme.current
