package ir.meelano.vpn.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ir.meelano.vpn.ui.theme.LocalMotionPrefs
import ir.meelano.vpn.ui.theme.LocalPalette

/*
 * The third dimension.
 *
 * Controls.kt already lights every face from above (gradient + hairline + shadow), which is what makes a
 * control read as plastic. What it did not have is the *side*: an object with no visible thickness floats.
 * These two modifiers give a control the two things a pressed physical object has and a Material one does
 * not:
 *
 *   - a WALL - the object's own thickness, visible as a strip under the face, darker toward its bottom,
 *     with one hairline of bounce light where it meets the surface it stands on;
 *   - a SQUASH - under the finger the wall collapses and the face tips a degree or two, so the gesture
 *     reads as work done *on* the object rather than a colour change of it.
 *
 * Costs are deliberately boring: two gradients and one line inside a node that already draws its own
 * background. No blur, no offscreen bitmap, nothing that animates by itself - at rest the wall is a
 * constant and the tilt's animation target is 0f, so a screen full of idle cards costs what it used to.
 * Reduced motion skips the tilt (motion) and keeps the wall (shape).
 */

/**
 * A slab of thickness [depth] under the face. Apply it **before** `.clip(shape)`: the wall has to be
 * allowed to extend below the layout box, while everything after the clip is the face, which covers the
 * wall's top - that covering is the whole illusion.
 *
 * @param pressed the control's own pressed state; the wall collapses to ~22% and the contact shadow with
 *   it, which is the same gesture as the existing 1.5px sink seen from the side.
 * @param wallTop/[wallBottom] null means "derive from the palette". A coloured CTA should pass a dark
 *   version of its own accent: a green button with a grey side looks laminated, one with a deep-green
 *   side looks moulded.
 */
@Composable
fun Modifier.meeExtruded(
    corner: Dp,
    depth: Dp = 4.dp,
    pressed: Boolean = false,
    enabled: Boolean = true,
    wallTop: Color? = null,
    wallBottom: Color? = null,
    shadowAlpha: Float = 1f,
): Modifier {
    val p = LocalPalette.current
    val target = depth * when {
        !enabled -> 0.45f          // a disabled control is not being held up by anything
        pressed -> 0.22f
        else -> 1f
    }
    // Animated, not switched: a wall that snaps to zero reads as a rendering glitch, and the spring keeps
    // it in step with the face scale that is already animating on the same press.
    val wall by animateDpAsState(
        targetValue = target,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = 900f),
        label = "meeWall",
    )
    val top = wallTop ?: p.bezelBottom
    val bottom = wallBottom ?: p.shade(0.78f)
    val contact = p.shade(0.5f * shadowAlpha)
    return this.drawBehind {
        val h = wall.toPx()                       // DrawScope is a Density, so this needs no with(density)
        if (h < 0.35f) return@drawBehind
        val r = CornerRadius(corner.toPx(), corner.toPx())
        val drop = h * 0.62f
        // 1. contact shadow - not a blur: a wide soft slab, darkest exactly where the object touches down
        drawRoundRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                0.32f to contact,
                1f to Color.Transparent,
                startY = size.height - h * 0.2f,
                endY = size.height + drop * 2.6f,
            ),
            topLeft = Offset(-h * 0.35f, size.height - h * 0.2f),
            size = Size(size.width + h * 0.7f, drop * 2.8f),
            cornerRadius = r,
        )
        // 2. the wall
        drawRoundRect(
            brush = Brush.verticalGradient(
                0f to top.copy(alpha = 0.98f),
                1f to bottom,
                startY = drop * 0.2f,
                endY = size.height + drop,
            ),
            topLeft = Offset(0f, drop * 0.2f),
            size = Size(size.width, size.height + drop),
            cornerRadius = r,
        )
        // 3. bounce light - one hairline where the slab meets the floor. Without it the wall looks like a
        //    hard-edged shadow; with it the object is standing on something.
        drawLine(
            color = p.tint(0.055f),
            start = Offset(corner.toPx() * 0.9f, size.height + drop - 0.6f),
            end = Offset(size.width - corner.toPx() * 0.9f, size.height + drop - 0.6f),
            strokeWidth = 1f.coerceAtMost(h * 0.3f),
        )
    }
}

/**
 * Tip the object a couple of degrees while it is pressed, around its far edge: what a slab fixed to a
 * screen does when you push near the middle of it. [tilt] is degrees - 1 to 3 reads as physical, 6 reads
 * as a card trick.
 *
 * `cameraDistance` is intentionally small for UI: a long focal length is what stops the far edge from
 * stretching, which is the difference between "tilted" and "warped".
 */
@Composable
fun Modifier.meeTiltOnPress(
    pressed: Boolean,
    enabled: Boolean = true,
    tilt: Float = 2.2f,
): Modifier {
    val reduced = LocalMotionPrefs.current.reduced
    val angle by animateFloatAsState(
        targetValue = if (pressed && enabled && !reduced) tilt else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 820f),
        label = "meeTilt",
    )
    val density = LocalDensity.current.density
    return this.graphicsLayer {
        if (angle == 0f) return@graphicsLayer
        rotationX = angle
        cameraDistance = 16f * density
    }
}
