package ir.meelano.vpn.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.meelano.vpn.R
import ir.meelano.vpn.ui.theme.LocalMotionPrefs
import ir.meelano.vpn.ui.theme.Meelano

/**
 * The control kit: buttons, icon buttons, chips, segmented, switch, panels.
 *
 * Why this is its own layer and not `MaterialTheme` defaults: a "designed" app is not a palette, it is
 * the *physics* of the things you press. Every control here is built from the same four rules, so a
 * button, a chip and a switch read as objects from one set:
 *
 *   1. LIGHT FROM ABOVE — every face is a vertical gradient: the top edge catches light (white ~17%),
 *      the bottom edge is shaded (black ~26%). A flat fill reads as a sticker; a lit face reads as
 *      plastic, and plastic is what makes a control look pressable before you press it.
 *   2. DEPTH IS FREE — a coloured `shadow()` plus one radial `drawRect` behind the control. No blur
 *      bitmap, no nine-patch: the glow collapses when pressed, which is what a lit object does when it
 *      moves away from the light.
 *   3. HAIRLINE EDGE — 1dp at ~13% white. It is what separates "raised" from "smudged" on OLED, and
 *      the only thing keeping a dark control visible on a dark surface in sunlight.
 *   4. THE GIVE — press = scale 0.972 + glow collapse + highlight dim + 1.5px sink, on a *spring*.
 *      Release rides the same spring, so a 90ms tap still looks like a physical object, not a state flip.
 *
 * Deliberately: no ripple. Ripple is Android 5's answer to "I heard you"; the give answers it better,
 * and a grey wash flattens the lit face that rule 1 exists to draw. `indication = null` on every
 * clickable here, on purpose.
 *
 * Motion: `LocalMotionPrefs.reduced` kills the halo breathing and the comet spin; it never kills the
 * press state, because a control that gives no feedback is not decorative motion, it is a broken
 * affordance.
 */

enum class BtnTone {
    /** the one action per screen: mint face, glowing halo, slow breath while idle */
    Primary,

    /** secondary action: raised surface, hairline edge, no glow */
    Tonal,

    /** tertiary: weightless until pressed */
    Ghost,

    /** destructive: warm red face. No glow — a red halo reads as an alarm light, not a button */
    Danger,
}

enum class BtnSize(val height: Dp, val hPad: Dp, val font: Int, val icon: Dp) {
    Small(34.dp, 12.dp, 13, 14.dp),
    Medium(46.dp, 16.dp, 14, 16.dp),
    Large(56.dp, 22.dp, 15, 18.dp),
}

/** The breath period, from the motion sheet (kept local so this file has one less cross-import). */
internal const val MeelanoBreathMs = 2400

private val HairlineEdge: Color = Color.White.copy(alpha = 0.13f)

/** convex face: lit top, shaded bottom — layered over any base colour, so it works on every tone */
private fun faceOverlay(pressed: Boolean): Brush = Brush.verticalGradient(
    0f to Color.White.copy(alpha = if (pressed) 0.10f else 0.17f),
    0.42f to Color.Transparent,
    1f to Color.Black.copy(alpha = if (pressed) 0.14f else 0.26f),
)

private fun pressSpring(): Spring<Float> = spring(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = 680f,
)

/* -------------------------------------------------------------------------------------------- button */

/**
 * The button. `iconRes = 0` means "no icon"; `trailingChevron` asks for a drawn caret — a caret says
 * "there is more behind this", an icon says "this does something", and mixing the two is how a
 * settings screen starts to lie about what tapping will do.
 */
@Composable
fun MeelanoButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    tone: BtnTone = BtnTone.Primary,
    size: BtnSize = BtnSize.Medium,
    iconRes: Int = 0,
    trailingChevron: Boolean = false,
    fill: Boolean = false,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val reduced = LocalMotionPrefs.current.reduced
    val shape = RoundedCornerShape(if (size == BtnSize.Large) 18.dp else 14.dp)

    val haloColor: Color
    val baseBrush: Brush
    val inkColor: Color
    val edgeColor: Color
    when (tone) {
        BtnTone.Primary -> {
            haloColor = Meelano.Accent
            baseBrush = Brush.verticalGradient(listOf(Meelano.Accent, Meelano.AccentDeep))
            inkColor = Meelano.AccentInk
            edgeColor = Color.White.copy(alpha = 0.28f)
        }
        BtnTone.Tonal -> {
            haloColor = Color.Transparent
            baseBrush = Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.045f)),
            )
            inkColor = Meelano.Text
            edgeColor = HairlineEdge
        }
        BtnTone.Ghost -> {
            haloColor = Color.Transparent
            baseBrush = Brush.verticalGradient(
                listOf(
                    if (pressed || !enabled) Color.White.copy(alpha = 0.07f) else Color.Transparent,
                    if (pressed) Color.Black.copy(alpha = 0.10f) else Color.Transparent,
                ),
            )
            inkColor = Meelano.Muted
            edgeColor = if (pressed) HairlineEdge else Color.Transparent
        }
        BtnTone.Danger -> {
            haloColor = Color.Transparent
            baseBrush = Brush.verticalGradient(
                listOf(Meelano.Danger.copy(alpha = 0.22f), Meelano.Danger.copy(alpha = 0.10f)),
            )
            inkColor = Meelano.Danger
            edgeColor = Meelano.Danger.copy(alpha = 0.42f)
        }
    }

    val glow = tone == BtnTone.Primary && enabled && haloColor != Color.Transparent
    // one delegate for both branches: a State that never moves when motion is reduced or the tone is flat
    val breath: State<Float> = if (glow && !reduced) {
        rememberInfiniteTransition(label = "cta").animateFloat(
            initialValue = 0.78f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(MeelanoBreathMs, easing = LinearEasing), RepeatMode.Reverse),
            label = "ctaPulse",
        )
    } else {
        remember { FixedState(1f) }
    }
    val lift by animateFloatAsState(
        targetValue = if (!enabled) 0.35f else if (pressed) 0.45f else 1f,
        animationSpec = pressSpring(),
        label = "lift",
    )
    val shrink by animateFloatAsState(
        targetValue = if (enabled && pressed) 0.972f else 1f,
        animationSpec = pressSpring(),
        label = "shrink",
    )

    Box(
        modifier
            .graphicsLayer {
                scaleX = shrink
                scaleY = shrink
                alpha = if (enabled) 1f else 0.55f
                translationY = if (pressed) 1.5f else 0f
            }
            // drawBehind runs on the *unpadded* node, so the halo is allowed to bleed past the face
            .then(
                if (glow) {
                    Modifier
                        .drawBehind {
                            val c = Offset(size.width / 2f, size.height / 2f)
                            val rad = maxOf(size.width, size.height) * 0.62f
                            val a = lift * breath.value
                            drawRect(
                                brush = Brush.radialGradient(
                                    0f to haloColor.copy(alpha = 0.30f * a),
                                    0.55f to haloColor.copy(alpha = 0.10f * a),
                                    1f to Color.Transparent,
                                    center = c,
                                    radius = rad,
                                ),
                            )
                        }
                        .padding(10.dp)
                } else {
                    Modifier
                },
            ),
    ) {
        Box(
            Modifier
                .then(if (fill) Modifier.fillMaxWidth() else Modifier)
                .height(size.height)
                .shadow(
                    elevation = if (tone == BtnTone.Primary) {
                        if (pressed) 2.dp else 12.dp
                    } else {
                        0.dp
                    },
                    shape = shape,
                    clip = false,
                    ambientColor = haloColor,
                    spotColor = haloColor,
                )
                .clip(shape)
                .background(baseBrush, shape)
                .background(faceOverlay(pressed), shape)
                .border(1.dp, edgeColor, shape)
                .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
                .padding(horizontal = size.hPad),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (loading) {
                    Comet(size = size.icon, ink = inkColor)
                    Spacer(Modifier.width(size.hPad * 0.6f))
                } else if (iconRes != 0) {
                    Icon(painterResource(iconRes), null, Modifier.size(size.icon), tint = inkColor)
                    Spacer(Modifier.width(size.hPad * 0.6f))
                }
                Text(
                    label,
                    color = inkColor,
                    fontSize = size.font.sp,
                    fontWeight = if (tone == BtnTone.Primary) FontWeight.Bold else FontWeight.SemiBold,
                    letterSpacing = 0.1.sp,
                    maxLines = 1,
                )
                if (trailingChevron) {
                    Spacer(Modifier.width(6.dp))
                    Chevron(ink = inkColor.copy(alpha = 0.75f), size = 9.dp)
                }
            }
        }
    }
}

/** A state that never moves, so the `breath` delegate has one type on both branches. */
private class FixedState(override val value: Float) : State<Float>

/* --------------------------------------------------------------------- a button that is a full action */

/**
 * Two-tone CTA: the label plus one line of consequence under it ("۳٫۲ مگابایت · نسخه ۲٫۰٫۱").
 * Update and permission flows need this shape: a pill with only a verb on it is how a user taps
 * something without knowing what it costs them.
 */
@Composable
fun MeelanoActionCard(
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    iconRes: Int = 0,
    accent: Color = Meelano.Accent,
    enabled: Boolean = true,
    secondary: String = "",
    onSecondary: () -> Unit = {},
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .shadow(10.dp, shape, clip = false, ambientColor = Color.Black, spotColor = accent)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(accent.copy(alpha = 0.14f), Meelano.Surface.copy(alpha = 0.97f)),
                ),
                shape,
            )
            .background(faceOverlay(false), shape)
            .border(1.dp, accent.copy(alpha = 0.30f), shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconRes != 0) {
            Box(
                Modifier
                    .padding(start = 12.dp)
                    .size(34.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
                    .border(1.dp, accent.copy(alpha = 0.34f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(iconRes), null, Modifier.size(17.dp), tint = accent)
            }
            Spacer(Modifier.width(11.dp))
        }
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(title, color = Meelano.Text, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(body, color = Meelano.Muted, fontSize = 11.5.sp, maxLines = 2)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 12.dp)) {
            MeelanoButton(
                label = action,
                onClick = onAction,
                enabled = enabled,
                loading = loading,
                tone = if (accent == Meelano.Accent) BtnTone.Primary else BtnTone.Tonal,
                size = BtnSize.Small,
            )
            if (secondary.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                MeelanoButton(
                    label = secondary,
                    onClick = onSecondary,
                    tone = BtnTone.Ghost,
                    size = BtnSize.Small,
                )
            }
        }
    }
}

/* ------------------------------------------------------------------------------- icon-only button */

/** The top-bar gear, a close X, a refresh. Same lighting model as the button, one square object. */
@Composable
fun MeelanoIconButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 44.dp,
    corner: Dp = 14.dp,
    enabled: Boolean = true,
    tint: Color = Meelano.Muted,
    prominent: Boolean = false,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val shrink by animateFloatAsState(
        targetValue = if (enabled && pressed) 0.92f else 1f,
        animationSpec = pressSpring(),
        label = "iconShrink",
    )
    val shape = RoundedCornerShape(corner)
    Box(
        modifier
            .scale(shrink)
            .size(sizeDp)
            .clip(shape)
            .background(
                if (prominent) {
                    Brush.verticalGradient(listOf(Meelano.Accent, Meelano.AccentDeep))
                } else {
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = if (pressed) 0.16f else 0.09f),
                            Color.White.copy(alpha = 0.03f),
                        ),
                    )
                },
                shape,
            )
            .background(faceOverlay(pressed), shape)
            .border(
                1.dp,
                if (prominent) Color.White.copy(alpha = 0.30f) else HairlineEdge,
                shape,
            )
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(iconRes),
            contentDescription,
            Modifier.size(sizeDp * 0.46f),
            tint = if (prominent) Meelano.AccentInk else tint,
        )
    }
}

/* -------------------------------------------------------------------------------------------- chip */

/**
 * Filter chips (همه / نزدیک‌ترین / سریع‌ترین) and the node selector. Selected is not only a different
 * colour: it is *closer to you* (lit face + halo) while unselected is a recessed well. That survives
 * colour-blindness, a broken colour filter and direct sunlight, which colour alone does not.
 */
@Composable
fun MeelanoChip(
    label: String,
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int = -1,
    enabled: Boolean = true,
    leadingIcon: Int = 0,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val shape = RoundedCornerShape(999.dp)
    val shrink by animateFloatAsState(
        targetValue = if (enabled && pressed) 0.955f else 1f,
        animationSpec = pressSpring(),
        label = "chipShrink",
    )
    val ink = when {
        !enabled -> Meelano.MutedFaint
        on -> Meelano.Accent
        else -> Meelano.Muted
    }
    Box(
        modifier
            .scale(shrink)
            .then(
                if (on) {
                    Modifier
                        .drawBehind {
                            drawRect(
                                brush = Brush.radialGradient(
                                    0f to Meelano.Accent.copy(alpha = 0.20f),
                                    1f to Color.Transparent,
                                    center = Offset(size.width / 2f, size.height / 2f),
                                    radius = maxOf(size.width, size.height) * 0.7f,
                                ),
                            )
                        }
                        .padding(6.dp)
                } else {
                    Modifier
                },
            ),
    ) {
        Row(
            Modifier
                .clip(shape)
                .background(
                    if (on) {
                        Brush.verticalGradient(
                            listOf(Meelano.Accent.copy(alpha = 0.20f), Meelano.Accent.copy(alpha = 0.07f)),
                        )
                    } else {
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.22f), Color.White.copy(alpha = 0.035f)),
                        )
                    },
                    shape,
                )
                .background(faceOverlay(on), shape)
                .border(1.dp, if (on) Meelano.Accent.copy(alpha = 0.55f) else HairlineEdge, shape)
                .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
                .padding(horizontal = 13.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != 0) {
                Icon(painterResource(leadingIcon), null, Modifier.size(13.dp), tint = ink)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                label,
                color = ink,
                fontSize = 12.5.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
            )
            if (count >= 0) {
                Spacer(Modifier.width(5.dp))
                Text(
                    count.toString(),
                    color = if (on) ink else Meelano.MutedFaint,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}

/* --------------------------------------------------------------------------------------- segmented */

/**
 * A carved well with one raised pill. The pill *slides* (spring, not a jump) because a control that
 * holds state should show where that state came from; the dark gradient at the top of the well is why
 * it reads as carved into the surface instead of a grey box with a box inside it.
 */
@Composable
fun MeelanoSegmented(
    items: List<String>,
    index: Int,
    onIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
    heightDp: Dp = 38.dp,
) {
    if (items.isEmpty()) return
    val shape = RoundedCornerShape(heightDp / 2f)
    val safe = index.coerceIn(0, items.size - 1)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(heightDp)
            .clip(shape)
            .background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.42f), Meelano.Well)),
                shape,
            )
            .border(1.dp, HairlineEdge, shape),
    ) {
        val cell = maxWidth / items.size
        val pad = 3.dp
        val dx by animateDpAsState(
            targetValue = (cell * safe) + pad,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 520f),
            label = "segSlide",
        )
        Box(
            Modifier
                .offset(x = dx)
                .width(cell - pad * 2)
                .fillMaxHeight()
                .padding(vertical = pad)
                .shadow(
                    6.dp,
                    RoundedCornerShape(999.dp),
                    ambientColor = Meelano.Accent,
                    spotColor = Meelano.Accent,
                )
                .clip(RoundedCornerShape(999.dp))
                .background(
                    Brush.verticalGradient(listOf(Meelano.Accent, Meelano.AccentDeep)),
                    RoundedCornerShape(999.dp),
                )
                .background(faceOverlay(false), RoundedCornerShape(999.dp)),
        )
        Row(Modifier.fillMaxSize()) {
            items.forEachIndexed { i, label ->
                val src = remember(i) { MutableInteractionSource() }
                val pressed by src.collectIsPressedAsState()
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            if (pressed && i != safe) Color.White.copy(alpha = 0.05f) else Color.Transparent,
                        )
                        .clickable(interactionSource = src, indication = null) { onIndex(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (i == safe) Meelano.AccentInk else Meelano.Muted,
                        fontSize = 12.sp,
                        fontWeight = if (i == safe) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/* ----------------------------------------------------------------------------------------- switch */

/**
 * The stock Material switch is the most "default Android" object in a VPN app. This one is a physical
 * rocker: a recessed track, a knob with a lit top face, and a shadow that shortens as the knob comes
 * to rest against the far wall. Mint only where mint means "on".
 */
@Composable
fun MeelanoSwitch(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    widthDp: Dp = 52.dp,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val pill = RoundedCornerShape(999.dp)
    val thumb = widthDp * 0.46f
    val pad = 2.5.dp
    val travel = widthDp - thumb - pad * 2
    val dx by animateDpAsState(
        targetValue = if (checked) travel else 0.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 560f),
        label = "knob",
    )
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.45f)
            .height(widthDp * 0.58f)
            .width(widthDp)
            .clip(pill)
            .background(
                if (checked) {
                    Brush.verticalGradient(listOf(Meelano.Accent.copy(alpha = 0.9f), Meelano.AccentDeep))
                } else {
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.5f), Color.White.copy(alpha = 0.06f)),
                    )
                },
                pill,
            )
            .background(faceOverlay(pressed), pill)
            .border(1.dp, if (checked) Meelano.Accent.copy(alpha = 0.6f) else HairlineEdge, pill)
            .clickable(interactionSource = src, indication = null, enabled = enabled) { onChange(!checked) }
            .padding(pad),
    ) {
        Box(
            Modifier
                .offset(x = dx)
                .size(thumb)
                .shadow(
                    elevation = if (pressed) 1.dp else 3.dp,
                    shape = CircleShape,
                    clip = false,
                    ambientColor = if (checked) Meelano.Accent else Color.Black,
                    spotColor = if (checked) Meelano.Accent else Color.Black,
                )
                .clip(CircleShape)
                .background(
                    if (checked) {
                        Brush.verticalGradient(listOf(Color.White, Color(0xFFD6E6DE)))
                    } else {
                        Brush.verticalGradient(listOf(Color(0xFF9FB2C4), Color(0xFF6B7E90)))
                    },
                    CircleShape,
                )
                .background(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.5f), Color.Transparent, Color.Black.copy(alpha = 0.18f)),
                    ),
                    CircleShape,
                )
                .border(1.dp, Color.Black.copy(alpha = 0.20f), CircleShape),
        )
    }
}

/* ----------------------------------------------------------------------------------------- panel */

/**
 * A raised group of rows. A sheet that is a flat list of rows reads as a spreadsheet; three of these
 * with a gap read as a device made of parts.
 */
@Composable
fun MeelanoPanel(
    modifier: Modifier = Modifier,
    title: String = "",
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .shadow(10.dp, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(Meelano.SurfaceHigh.copy(alpha = 0.95f), Meelano.Surface.copy(alpha = 0.97f)),
                ),
                shape,
            )
            .background(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.05f), Color.Transparent)),
                shape,
            )
            .border(1.dp, HairlineEdge, shape)
            .padding(top = if (title.isEmpty()) 2.dp else 10.dp, bottom = 2.dp),
    ) {
        if (title.isNotEmpty()) {
            Text(
                title,
                color = Meelano.MutedFaint,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp,
                modifier = Modifier.padding(start = 14.dp, bottom = 4.dp),
            )
        }
        content()
    }
}

/** A hairline that fades at both ends — a full-width divider inside a rounded panel looks pasted on. */
@Composable
fun PanelDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .height(1.dp)
            .background(Brush.horizontalGradient(listOf(Color.Transparent, Meelano.Line, Color.Transparent))),
    )
}

/** The lit state dot: the top bar and the sheet headers are readable from this alone. */
@Composable
fun StateDot(on: Boolean, label: String = "", modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .padding(5.dp)
                .drawBehind {
                    if (on) {
                        drawRect(
                            brush = Brush.radialGradient(
                                0f to Meelano.Accent.copy(alpha = 0.55f),
                                1f to Color.Transparent,
                                center = Offset(size.width / 2f, size.height / 2f),
                                radius = maxOf(size.width, size.height) / 2f,
                            ),
                        )
                    }
                }
                .padding(5.dp)
                .size(7.dp)
                .clip(CircleShape)
                .background(if (on) Meelano.Accent else Meelano.MutedFaint),
        )
        if (label.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(label, color = Meelano.Muted, fontSize = 11.5.sp, maxLines = 1)
        }
    }
}

/** Nothing-here card: an icon in a lit well, one line, one way out. No dead ends in this app. */
@Composable
fun MeelanoEmptyState(
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    iconRes: Int = R.drawable.ic_bolt,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 26.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.verticalGradient(listOf(Meelano.SurfaceHigh, Meelano.Well)),
                    RoundedCornerShape(20.dp),
                )
                .background(faceOverlay(false), RoundedCornerShape(20.dp))
                .border(1.dp, HairlineEdge, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(iconRes), null, Modifier.size(24.dp), tint = Meelano.Accent.copy(alpha = 0.9f))
        }
        Spacer(Modifier.height(12.dp))
        Text(title, color = Meelano.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text(body, color = Meelano.MutedFaint, fontSize = 12.sp, modifier = Modifier.padding(bottom = 14.dp))
        MeelanoButton(action, onAction, size = BtnSize.Small, tone = BtnTone.Tonal)
    }
}

/* ---------------------------------------------------------------------------------- small shapes */

/** A caret that says "this opens something". Drawn, so it costs 0 bytes and never blurs. */
@Composable
private fun Chevron(ink: Color, size: Dp = 9.dp) {
    Canvas(Modifier.size(size)) {
        val st = (this.size.minDimension * 0.16f).coerceAtLeast(1.6f)
        drawLine(
            color = ink,
            start = Offset(this.size.width * 0.16f, this.size.height * 0.38f),
            end = Offset(this.size.width * 0.5f, this.size.height * 0.68f),
            strokeWidth = st,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = ink,
            start = Offset(this.size.width * 0.5f, this.size.height * 0.68f),
            end = Offset(this.size.width * 0.84f, this.size.height * 0.38f),
            strokeWidth = st,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * Loading: eight dots, one bright, the brightness running. At 14dp an arc spinner turns to antialias
 * mush and reads as "waiting"; a comet reads as "this control is doing the thing you asked for".
 */
@Composable
private fun Comet(size: Dp = 15.dp, ink: Color = Meelano.AccentInk) {
    val reduced = LocalMotionPrefs.current.reduced
    val t = rememberInfiniteTransition(label = "comet")
    val spin by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "cometSpin",
    )
    val f = if (reduced) 0.25f else spin
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val dot = (r * 0.26f).coerceAtLeast(1.2f)
        for (i in 0 until 8) {
            val a = 2.0 * Math.PI * (i / 8.0 + f)
            val alpha = (1f - (i / 8f))
            drawCircle(
                color = ink.copy(alpha = 0.18f + alpha * 0.82f),
                radius = dot * (0.72f + alpha * 0.28f),
                center = Offset(
                    r + (r - dot) * kotlin.math.cos(a).toFloat(),
                    r + (r - dot) * kotlin.math.sin(a).toFloat(),
                ),
            )
        }
    }
}
