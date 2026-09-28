package ir.meelano.vpn.ui.theme

import android.app.Activity
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/*
  The M3 schemes are *derived* from the palette (see Color.kt) rather than written out again: the
  day this app had a second list of literals, the light theme became a lie - M3 surfaces flipped to
  white while every hand-painted screen kept reading the dark object. One source, two consumers.
 */
private fun schemeOf(p: Palette) = if (p.isDark) {
    darkColorScheme(
        primary = p.accent,
        onPrimary = p.accentInk,
        secondary = p.info,
        background = p.bg,
        onBackground = p.text,
        surface = p.surface,
        onSurface = p.text,
        surfaceVariant = p.surfaceHigh,
        onSurfaceVariant = p.muted,
        outline = p.line,
        outlineVariant = p.line,
        error = p.danger,
        onError = Color(0xFF2B0D0D),
        scrim = Color(0xCC04070A),
    )
} else {
    lightColorScheme(
        primary = p.accent,
        onPrimary = Color(0xFFFFFFFF),
        secondary = p.info,
        background = p.bg,
        onBackground = p.text,
        surface = p.surface,
        onSurface = p.text,
        surfaceVariant = p.surfaceHigh,
        onSurfaceVariant = p.muted,
        outline = p.line,
        outlineVariant = p.line,
        error = p.danger,
        onError = Color(0xFFFFFFFF),
        // a 80%-black scrim over a light app is correct; the dark one's 0xCC would hide the sheet's
        // own elevation shadow and read as a power-off screen.
        scrim = Color(0x8A1B2630),
    )
}

@Composable
fun MeelanoTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamic: Boolean = false,          // off by default: Material You eats the brand
    reducedMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        val w = (LocalContext.current as? Activity)?.window
        SideEffect {
            w?.let {
                it.statusBarColor = Color.Transparent.toArgb()
                it.navigationBarColor = Color.Transparent.toArgb()
                WindowCompat.setDecorFitsSystemWindows(it, false)
                val ctrl = WindowCompat.getInsetsController(it, view)
                ctrl.isAppearanceLightStatusBars = !dark
                ctrl.isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(LocalContext.current)
            else dynamicLightColorScheme(LocalContext.current)
        dark -> schemeOf(Palette.Dark)
        else -> schemeOf(Palette.Light)
    }
    val animatorScale = rememberSystemAnimatorScale()
    CompositionLocalProvider(
        LocalSpacing provides Spacing(),
        // the palette is what the hand-painted screens actually read (MaterialTheme's scheme only
        // reaches M3 components, and almost nothing in this app is an M3 component)
        LocalPalette provides (if (dark) Palette.Dark else Palette.Light),
        // the system's own "no animations" is folded in here, once, so every consumer of `reduced`
        // (enter/exit tweens *and* loopsAllowed) respects it without each screen re-reading Settings
        LocalMotionPrefs provides MotionPrefs(reducedMotion || animatorScale <= 0f, animatorScale),
        LocalDark provides dark,
    ) {
        MaterialTheme(colorScheme = scheme, typography = MeelanoType.asMaterial3(), shapes = MeelanoShapes) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Box { content() }
            }
        }
    }
}

val LocalDark = staticCompositionLocalOf { true }

/** Mirrors `AppSettings.THEME_*`; kept here so the Activity never imports `data` for a UI enum. */
object AppThemeMode {
    const val SYSTEM = 0
    const val DARK = 1
    const val LIGHT = 2
}

/** Shapes alias kept so screens can ask for `MeelanoShapes` directly. */
val MeelanoShapesAlias: Shapes get() = MeelanoShapes

/**
 * The system animator scale, live.
 *
 * This used to be a hardcoded `1f` with a comment saying "read Settings.Global if you want it live", which
 * meant the OS-level "remove animations" toggle changed nothing: the app's 2.4s breathing loops kept
 * running on a phone that had been told to animate nothing at all, and the QA trick of zeroing the scale
 * for screenshots showed a UI that no real setting produces.
 *
 * A ContentObserver rather than a single read, because this is flipped *while* the app is open.
 */
@Composable
private fun rememberSystemAnimatorScale(): Float {
    val resolver = LocalContext.current.contentResolver
    var scale by remember { mutableStateOf(readAnimatorScale(resolver)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { scale = readAnimatorScale(resolver) }
        }
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        runCatching { resolver.registerContentObserver(uri, false, observer) }
        onDispose { runCatching { resolver.unregisterContentObserver(observer) } }
    }
    return scale
}

private fun readAnimatorScale(resolver: android.content.ContentResolver): Float = runCatching {
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
}.getOrDefault(1f)
