package ir.meelano.vpn.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Motion tokens. Every duration here has a job; nothing is decorative loops.
 *
 *   enter 200ms decelerate  - content arriving must feel like it landed, not bounced
 *   exit  150ms accelerate  - leaving is faster than arriving (perceived responsiveness)
 *   press spring            - the physical give of the connect core
 *   breath 2400ms           - idle only: "I am alive and ready", the slowest thing on screen
 *   sheen 6000ms            - connected only: ambient, at the edge of attention
 *
 * Nothing during `connecting` is longer than 400ms. During the seconds the tunnel takes to come up,
 * a slow animation reads as a hang; a *staged* one reads as progress.
 */
object Motion {
    val enter = tween<Float>(200, easing = FastOutSlowInEasing)
    val exit = tween<Float>(150, easing = LinearOutSlowInEasing)
    val enterDp = tween<Dp>(200, easing = FastOutSlowInEasing)
    val state = tween<Float>(240, easing = FastOutSlowInEasing)
    val press = Spring.DampingRatioMediumBouncy to 520f     // damping, stiffness
    val breathMs = 2400
    val sheenMs = 6000
    val sweepMs = 1100
    val errorFlashMs = 180                                   // one flash, then silence

    fun <T> tween200(): TweenSpec<T> = tween(200, easing = FastOutSlowInEasing)
}

/** Set to true from `Settings ▸ کاهش انیمیشن` or when the system's animator scale is 0. */
data class MotionPrefs(val reduced: Boolean = false, val systemScale: Float = 1f) {
    fun enter(): TweenSpec<Float> = if (reduced) tween(0) else Motion.enter
    fun exit(): TweenSpec<Float> = if (reduced) tween(0) else Motion.exit
    fun loopsAllowed(): Boolean = !reduced && systemScale > 0f
}

@Suppress("CompositionLocalAllowlist")
val LocalMotionPrefs: ProvidableCompositionLocal<MotionPrefs> =
    staticCompositionLocalOf { MotionPrefs() }

/** A ring/halo shadow instead of blur: same depth cue, ~free on the GPU. */
fun elevationDp(level: Int): Dp = when (level) {
    0 -> 0.dp
    1 -> 4.dp
    2 -> 12.dp
    3 -> 26.dp
    else -> 40.dp
}

@Composable
fun rememberReducedMotion(): Boolean = LocalMotionPrefs.current.reduced
