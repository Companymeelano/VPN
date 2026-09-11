package ir.meelano.vpn.ui.theme

import android.app.Activity
import android.os.Build
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkScheme = darkColorScheme(
    primary = Meelano.Accent,
    onPrimary = Meelano.AccentInk,
    secondary = Meelano.Info,
    background = Meelano.Bg,
    onBackground = Meelano.Text,
    surface = Meelano.Surface,
    onSurface = Meelano.Text,
    surfaceVariant = Meelano.SurfaceHigh,
    onSurfaceVariant = Meelano.Muted,
    outline = Meelano.Line,
    outlineVariant = Meelano.Line,
    error = Meelano.Danger,
    onError = Color(0xFF2B0D0D),
    scrim = Color(0xCC04070A),
)

private val LightScheme = lightColorScheme(
    primary = Meelano.LAccent,
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF0B6C93),
    background = Meelano.LBg,
    onBackground = Meelano.LText,
    surface = Meelano.LSurface,
    onSurface = Meelano.LText,
    surfaceVariant = Color(0xFFEDF1F5),
    onSurfaceVariant = Meelano.LMuted,
    outline = Meelano.LLine,
    outlineVariant = Meelano.LLine,
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

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
        dark -> DarkScheme
        else -> LightScheme
    }
    val animatorScale = 1f      // read Settings.Global.TRANSITION_ANIMATION_SCALE if you want it live
    CompositionLocalProvider(
        LocalSpacing provides Spacing(),
        LocalMotionPrefs provides MotionPrefs(reducedMotion, animatorScale),
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
