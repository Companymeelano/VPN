package ir.meelano.vpn.ui

import ir.meelano.vpn.ui.theme.Meelano
import ir.meelano.vpn.ui.theme.Motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import ir.meelano.vpn.R
import ir.meelano.vpn.ui.theme.LocalPalette
import ir.meelano.vpn.ui.theme.LocalSpacing
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.meelano.vpn.data.FeedNode
import ir.meelano.vpn.vpn.ConnectPhase
import ir.meelano.vpn.vpn.Traffic
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.foundation.border
import androidx.compose.material3.Icon
import androidx.compose.ui.res.stringResource

/**
 * The one control the brief asks for: the connect/disconnect core, at the TOP of the screen,
 * with the live speed and the status readable inside it.
 *
 * Design intent (why it looks like this):
 *  - it is the only "physical" object on the screen: a machined bezel with an inner well, so it
 *    reads as pressable without a label saying "press me";
 *  - state is expressed by light, not by colour alone: idle = a slow breath, connecting = a sweep
 *    with a REAL percentage from the connect stages, connected = a solid ring with a rotating
 *    sheen, error = a single hard flash, no animation loop (a spinning thing on an error makes
 *    people wait instead of retrying);
 *  - numbers use tabular figures, otherwise latency/speed jitter horizontally and the whole
 *    screen looks unstable;
 *  - nothing here animates longer than 400 ms while connecting: perceived speed is part of the fix
 *    for "the app hangs".
 */
@Composable
fun VpnControlPanel(
    phase: ConnectPhase,
    traffic: Traffic,
    node: FeedNode?,
    onToggle: () -> Unit,
    onOpenSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val connected = phase is ConnectPhase.Connected
    val connecting = phase !is ConnectPhase.Idle && phase !is ConnectPhase.Connected && phase !is ConnectPhase.Failed
    val failed = phase is ConnectPhase.Failed
    val progress = when (phase) {
        is ConnectPhase.Idle -> 0f
        is ConnectPhase.Connected -> 1f
        is ConnectPhase.Failed -> 0f
        else -> phase.progress
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(p.surface, p.bg)
                )
            )
            .padding(top = 18.dp, bottom = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ConnectRing(
            progress = progress,
            connected = connected,
            connecting = connecting,
            failed = failed,
            onPress = onToggle,
            onLongPress = onOpenSheet,
        )
        Spacer(Modifier.height(14.dp))
        StatusLine(phase = phase, node = node)
        Spacer(Modifier.height(12.dp))
        SpeedStrip(traffic = traffic, enabled = connected)
        Spacer(Modifier.height(10.dp))
        // long-press on the ring also opens the list, but nobody discovers a gesture by accident:
        // the affordance is drawn, and it is a caret because it opens something
        MeelanoButton(
            label = stringResource(R.string.sheet_servers),
            onClick = onOpenSheet,
            tone = BtnTone.Ghost,
            size = BtnSize.Small,
            iconRes = R.drawable.ic_server,
            trailingChevron = true,
        )
    }
}

/** 3D-ish pressable core: bezel + inner well + state ring. Drawn, not imaged -> scales, themes, dark/light. */
@Composable
private fun ConnectRing(
    progress: Float,
    connected: Boolean,
    connecting: Boolean,
    failed: Boolean,
    onPress: () -> Unit,
    onLongPress: () -> Unit,
    size: Int = 172,
) {
    val p = LocalPalette.current
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 520f),
        label = "press",
    )

    // idle: slow breath. connected: sheen rotation. Both are GPU-cheap (one transform each).
    val breath = rememberInfiniteTransition(label = "breath")
    val breathe by breath.animateFloat(
        initialValue = 0.86f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "breathe",
    )
    val sheen by breath.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart),
        label = "sheen",
    )
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(connecting) {
        if (connecting) {
            while (true) sweep.animateTo(1f, tween(1100, easing = LinearEasing))
            sweep.snapTo(0f)
        } else {
            sweep.animateTo(0f, Motion.exit)
        }
    }
    val appear = remember { Animatable(0.94f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, Motion.enter) }

    val ringColor = when {
        failed -> p.danger
        connected -> p.accent
        else -> p.accent.copy(alpha = 0.85f)
    }
    val glowAlpha = when {
        failed -> 1f
        connected -> 0.9f
        connecting -> 0.55f + 0.25f * sin((sweep.value * 2 * PI)).toFloat()
        else -> 0.18f * breathe
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size.dp)
            .scale(scale * appear.value)
            .shadow(
                elevation = if (connected || failed) 26.dp else 12.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = ringColor.copy(alpha = 0.35f * glowAlpha),
                spotColor = ringColor.copy(alpha = 0.55f * glowAlpha),
            )
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(p.bezelTop, p.bezelBottom),
                    start = Offset.Zero,
                    end = Offset(size * 0.9f, size * 1.1f),
                )
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    },
                    onTap = {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        onPress()
                    },
                    onLongPress = { onLongPress() },
                )
            },
    ) {
        // the inner well: an inverted gradient is what makes it read as "recessed"
        Box(
            Modifier
                .size((size * 0.83f).dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(p.bg, p.well),
                        center = Offset(size * 0.44f, size * 0.38f),
                        radius = size * 0.55f,
                    )
                )
        )
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 6.dp.toPx()
            val inset = stroke / 2 + 5.dp.toPx()
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            val top = Offset(inset, inset)

            // track
            drawArc(
                color = p.tint(0.06f), 0f, 360f, false,
                topLeft = top, size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            // state arc: real progress while connecting, full ring when connected
            val sweepAngle = when {
                connected || failed -> 360f
                else -> (progress.coerceIn(0f, 1f) * 360f)
            }
            drawArc(
                brush = Brush.sweepGradient(
                    0f to ringColor.copy(alpha = 0.25f),
                    0.55f to ringColor,
                    1f to p.accentDeep,
                ),
                startAngle = -90f, sweepAngle = sweepAngle, useCenter = false,
                topLeft = top, size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            // indeterminate sweep on top of the determinate arc: shows "still working" honestly
            if (connecting && sweep.value > 0f) {
                drawArc(
                    color = p.tint(0.5f),
                    startAngle = -90f + sweep.value * 360f, sweepAngle = 28f, useCenter = false,
                    topLeft = top, size = arcSize,
                    style = Stroke(width = stroke * 0.5f, cap = StrokeCap.Round),
                )
            }
            // idle hint ring
            if (!connected && !connecting && !failed) {
                drawCircle(
                    color = p.tint(0.10f * breathe),
                    radius = arcSize.width / 2,
                    center = Offset(size / 2f, size / 2f),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 12f))),
                )
            }
            // rotating sheen while connected: one draw, cheap
            if (connected) {
                rotate(sheen, pivot = Offset(size / 2f, size / 2f)) {
                    drawArc(
                        brush = Brush.linearGradient(
                            listOf(Color.Transparent, p.tint(0.16f), Color.Transparent)
                        ),
                        startAngle = 0f, sweepAngle = 70f, useCenter = false,
                        topLeft = top, size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Butt),
                    )
                }
            }
        }
        PowerGlyph(connected = connected, failed = failed, size = size)
    }
}

/** The mark inside the well: a power glyph that morphs into a "connected" check-ish dot. */
@Composable
private fun PowerGlyph(connected: Boolean, failed: Boolean, size: Int) {
    val p = LocalPalette.current
    val tint = when {
        failed -> p.danger
        connected -> p.accent
        else -> p.muted
    }
    val scale by animateFloatAsState(if (connected) 1.06f else 1f, Motion.enter, label = "glyph")
    Canvas(Modifier.size((size * 0.30f).dp).scale(scale)) {
        val r = this.size.minDimension / 2
        val st = (r * 0.16f).coerceAtLeast(3f)
        drawArc(
            color = tint, startAngle = -60f, sweepAngle = 300f, useCenter = false,
            style = Stroke(width = st, cap = StrokeCap.Round),
            topLeft = Offset(st, st * 1.6f), size = Size(this.size.width - st * 2, this.size.height - st * 2.4f),
        )
        drawLine(
            color = tint, start = Offset(r, 0f), end = Offset(r, r * 0.62f),
            strokeWidth = st, cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun StatusLine(phase: ConnectPhase, node: FeedNode?) {
    val p = LocalPalette.current
    val (text, color) = when (phase) {
        is ConnectPhase.Idle -> "برای اتصال لمس کنید" to p.muted
        is ConnectPhase.Connected -> (node?.name ?: "Vip Meelano") to p.text
        is ConnectPhase.Failed -> when (phase.reason) {
            // the one failure that is a property of *this build*, not of the network: say so plainly
            ir.meelano.vpn.vpn.CoreApi.NOT_LINKED -> "هسته‌ی تونل در این بیلد وصل نشده است" to p.warn
            else -> "اتصال برقرار نشد" to p.danger
        }
        else -> "در حال اتصال… ${(phase.progress * 100).toInt()}%" to p.muted
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (node != null && phase is ConnectPhase.Connected) {
            Flag(cc = node.cc)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            color = color,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            style = TextStyle(fontFeatureSettings = "tnum"),
        )
    }
}

/** Speed + session, tabular so digits never reflow. Skeleton (not a spinner) when unknown. */
@Composable
private fun SpeedStrip(traffic: Traffic, enabled: Boolean) {
    val p = LocalPalette.current
    // a carved well, not three floating numbers: the strip is the readout of an instrument, and an
    // instrument has a bezel. The dark-to-ink vertical gradient is what makes it read as recessed.
    val shape = RoundedCornerShape(14.dp)
    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp)
            .clip(shape)
            .background(
                Brush.verticalGradient(listOf(p.shade(0.34f), p.well)),
                shape,
            )
            .background(
                Brush.verticalGradient(listOf(p.shade(0.22f), Color.Transparent)),
                shape,
            )
            .border(1.dp, p.tint(0.08f), shape)
            .padding(vertical = 9.dp, horizontal = 10.dp),
    ) {
        val style = TextStyle(fontFeatureSettings = "tnum", fontWeight = FontWeight.SemiBold)
        Metric(if (enabled) humanRate(traffic.rxPerSec) else "—", p.accent, style, R.drawable.ic_download)
        Metric(if (enabled) humanRate(traffic.txPerSec) else "—", p.muted, style, R.drawable.ic_upload)
        Metric(if (enabled) humanDuration(traffic.seconds) else "—", p.text, style, 0)
    }
}

@Composable
private fun Metric(value: String, color: Color, style: TextStyle, iconRes: Int = 0) {
    val p = LocalPalette.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (iconRes != 0) {
            Icon(
                painterResource(iconRes), null,
                Modifier.size(12.dp), tint = color.copy(alpha = 0.75f),
            )
        } else {
            Text("سشن", color = p.muted, fontSize = 10.sp)
        }
        Spacer(Modifier.height(2.dp))
        Text(value, color = color, fontSize = 13.sp, style = style)
    }
}

@Composable
private fun Flag(cc: String?, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val sp = LocalSpacing.current
    val res = Flags.resFor(cc)
    if (res == 0) {
        // the neutral tile: same box, same corner, so an unknown country never collapses the row
        Box(
            modifier
                .width(sp.flagW).height(sp.flagH)
                .clip(RoundedCornerShape(3.dp))
                .background(p.tint(0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                cc?.trim()?.uppercase()?.take(2) ?: "??",
                fontSize = 6.5.sp, color = p.mutedFaint, fontWeight = FontWeight.Bold,
            )
        }
    } else {
        Image(
            painter = painterResource(res),
            contentDescription = null,          // the country name is already in the row's text
            modifier = modifier.width(sp.flagW).height(sp.flagH).clip(RoundedCornerShape(3.dp)),
            contentScale = ContentScale.FillBounds,
        )
    }
}
fun humanRate(bytesPerSec: Long): String = when {
    bytesPerSec <= 0 -> "0 KB/s"
    bytesPerSec < 1024 -> "$bytesPerSec B/s"
    bytesPerSec < 1024 * 1024 -> String.format("%.0f KB/s", bytesPerSec / 1024.0)
    else -> String.format("%.1f MB/s", bytesPerSec / 1048576.0)
}

fun humanDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}

/**
 * Country flags are OUR artwork: never an emoji, never the vendor's wording.
 *
 * Two design reasons and one engineering reason for hand-drawn 19x14 vector tiles:
 *  - emoji flags are a *font* feature: on Samsung/OneUI down to Android 9, and on any device with a
 *    replaced system font, they render as "DE" inside a box - the broken look this app exists to kill;
 *  - a 19x14 tile with a 3dp corner sits on the text baseline and reads as a flag next to 14sp text;
 *  - a static map instead of `getIdentifier`: a name lookup per row is measurable in a 40-row list and
 *    R8 can shrink away anything it cannot see referenced, which ships as "flags missing in release".
 *
 * Generated from design/flags/gen-flags.py - run that script, then re-run this generator.
 */
object Flags {
    private val map: Map<String, Int> = mapOf(
        "AE" to R.drawable.flag_ae,
        "AM" to R.drawable.flag_am,
        "AR" to R.drawable.flag_ar,
        "AT" to R.drawable.flag_at,
        "AU" to R.drawable.flag_au,
        "AZ" to R.drawable.flag_az,
        "BE" to R.drawable.flag_be,
        "BG" to R.drawable.flag_bg,
        "BR" to R.drawable.flag_br,
        "CA" to R.drawable.flag_ca,
        "CH" to R.drawable.flag_ch,
        "CZ" to R.drawable.flag_cz,
        "DE" to R.drawable.flag_de,
        "DK" to R.drawable.flag_dk,
        "EE" to R.drawable.flag_ee,
        "EG" to R.drawable.flag_eg,
        "ES" to R.drawable.flag_es,
        "FI" to R.drawable.flag_fi,
        "FR" to R.drawable.flag_fr,
        "GB" to R.drawable.flag_gb,
        "GE" to R.drawable.flag_ge,
        "GR" to R.drawable.flag_gr,
        "HK" to R.drawable.flag_hk,
        "HU" to R.drawable.flag_hu,
        "IN" to R.drawable.flag_in,
        "IQ" to R.drawable.flag_iq,
        "IR" to R.drawable.flag_ir,
        "IS" to R.drawable.flag_is,
        "IT" to R.drawable.flag_it,
        "JP" to R.drawable.flag_jp,
        "KR" to R.drawable.flag_kr,
        "KZ" to R.drawable.flag_kz,
        "LT" to R.drawable.flag_lt,
        "LU" to R.drawable.flag_lu,
        "LV" to R.drawable.flag_lv,
        "MD" to R.drawable.flag_md,
        "NL" to R.drawable.flag_nl,
        "NO" to R.drawable.flag_no,
        "NZ" to R.drawable.flag_nz,
        "PL" to R.drawable.flag_pl,
        "PT" to R.drawable.flag_pt,
        "QA" to R.drawable.flag_qa,
        "RO" to R.drawable.flag_ro,
        "RS" to R.drawable.flag_rs,
        "RU" to R.drawable.flag_ru,
        "SE" to R.drawable.flag_se,
        "SG" to R.drawable.flag_sg,
        "SI" to R.drawable.flag_si,
        "SK" to R.drawable.flag_sk,
        "TR" to R.drawable.flag_tr,
        "UA" to R.drawable.flag_ua,
        "US" to R.drawable.flag_us,
    )

    /** unknown code -> 0 and the row draws the neutral chip; we never guess a flag */
    @androidx.annotation.DrawableRes
    fun resFor(cc: String?): Int =
        cc?.trim()?.uppercase()?.takeIf { it.length == 2 }?.let { map[it] } ?: 0

    fun isKnown(cc: String?): Boolean = resFor(cc) != 0
    fun codes(): Set<String> = map.keys
}

/** Server rows: the grade + a quality bar, so a slow-but-usable node is still readable. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ServerRow(
    node: FeedNode,
    selected: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
) {
    val p = LocalPalette.current
    val accent = when (node.grade) {
        "A" -> p.accent
        "B" -> p.info
        "C" -> p.warn
        else -> p.danger
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) p.accent.copy(alpha = 0.08f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onTogglePin)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Flag(cc = node.cc)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(node.name, color = p.text, fontSize = 14.sp)
            Text(
                node.subtitle, color = p.muted, fontSize = 11.sp,
                style = TextStyle(fontFeatureSettings = "tnum"),
            )
        }
        // quality bar instead of a raw number: comparable at a glance across rows
        Box(
            Modifier
                .width(56.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(p.tint(0.08f))
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(qualityFraction(node))
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent)
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            node.latencyLabel, color = accent, fontSize = 12.sp,
            style = TextStyle(fontFeatureSettings = "tnum"),
        )
    }
}

private fun qualityFraction(n: FeedNode): Float {
    val lat = n.latencyMs?.toFloat() ?: 3000f
    val speed = 1f - (lat / 2500f).coerceIn(0f, 1f)
    val trust = n.reliability.coerceIn(0f, 1f)
    val samples = (n.samples / 8f).coerceIn(0.35f, 1f)   // new nodes start partially filled, not empty
    return (speed * 0.45f + trust * 0.4f + 0.15f * samples).coerceIn(0.06f, 1f)
}
