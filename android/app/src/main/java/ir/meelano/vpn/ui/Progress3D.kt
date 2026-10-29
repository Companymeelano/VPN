package ir.meelano.vpn.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.meelano.vpn.data.SyncProgress
import ir.meelano.vpn.ui.theme.LocalPalette

/**
 * The 3D progress kit: every loading/percent surface in the app is built from these, so "attractive
 * and creative" is one coherent language (bezel → carved well → glowing fill) instead of five
 * different spinners. Numbers are never decorative: the dial prints the overall percent, each bar
 * prints its own stage percent and its real `done/total` counters from data/SyncProgress.
 *
 * Depth is the same recipe the ConnectRing and the control kit use: a raised bezel (light top,
 * dark bottom), an inner well that reads as carved, and a fill with a top sheen so the bar has a
 * "glass over metal" face. Reduced-motion users get the same geometry with no interpolation.
 */

@Composable
fun StageDial3D(
    overallFraction: Float,
    percentText: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 104.dp,
) {
    val p = LocalPalette.current
    val reduced = ir.meelano.vpn.data.AppSettings.reducedMotion
    val sweepTarget = overallFraction.coerceIn(0f, 1f)
    val sweep by animateFloatAsState(
        targetValue = sweepTarget,
        animationSpec = if (reduced) tween(0) else tween(420, easing = LinearEasing),
        label = "dialSweep",
    )
    val ink = if (active) p.accent else p.muted
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = this.size.minDimension * 0.11f
            val inset = stroke / 2f + 2.dp.toPx()
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            // the track: a groove cut into the well
            drawArc(
                color = p.shade(0.55f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            // the fill: accent, lit from the top like every raised element in the kit
            if (sweep > 0.001f) {
                drawArc(
                    brush = Brush.verticalGradient(
                        listOf(p.accent, p.accentDeep),
                        startY = 0f,
                        endY = this.size.height,
                    ),
                    startAngle = -90f,
                    sweepAngle = 360f * sweep,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                percentText,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = if (sweepTarget >= 1f) p.accent else p.text,
                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
            )
            Text(
                if (sweepTarget >= 1f) "تکمیل" else "در حال کار",
                fontSize = 9.5.sp,
                color = p.faint,
            )
        }
    }
}

@Composable
fun StageBar3D(
    label: String,
    stageFraction: Float,
    detail: String,
    active: Boolean,
    reached: Boolean,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val reduced = ir.meelano.vpn.data.AppSettings.reducedMotion
    val target = stageFraction.coerceIn(0f, 1f)
    val fill by animateFloatAsState(
        targetValue = target,
        animationSpec = if (reduced) tween(0) else tween(380, easing = LinearEasing),
        label = "barFill",
    )
    val shape = RoundedCornerShape(999.dp)
    val labelInk = when {
        active -> p.text
        reached -> p.muted
        else -> p.faint
    }
    Row(
        modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 12.sp, color = labelInk, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium)
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .weight(1f)
                .height(12.dp)
                .clip(shape)
                // carved well: dark at the top of the groove, solid below
                .background(Brush.verticalGradient(listOf(p.shade(0.42f), p.well)), shape)
                .border(1.dp, p.tint(0.10f), shape),
        ) {
            if (fill > 0.001f) {
                Box(
                    Modifier
                        .fillMaxWidth(fill)
                        .height(12.dp)
                        .clip(shape)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    if (active) p.accent else p.accent.copy(alpha = 0.72f),
                                    p.accentDeep,
                                ),
                            ),
                            shape,
                        ),
                )
                // glass sheen along the top of the fill
                Box(
                    Modifier
                        .fillMaxWidth(fill)
                        .height(5.dp)
                        .clip(shape)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0.28f), Color.Transparent),
                            ),
                        ),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            detail,
            fontSize = 10.5.sp,
            color = if (active) p.accent else p.faint,
            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
        )
    }
}

/**
 * The full staged view: one dial for the whole run + one bar per real stage. Stages that have not
 * started yet are shown greyed at 0 % (never hidden — a user who sees five rows appear one by one
 * thinks the app is inventing work).
 */
@Composable
fun SyncProgressPanel(progress: SyncProgress, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(p.surfaceHigh, p.surface)), shape)
            .border(1.dp, p.tint(0.12f), shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StageDial3D(
            overallFraction = progress.overallFraction,
            percentText = progress.percentText,
            active = progress.active,
        )
        Spacer(Modifier.height(10.dp))
        for (stage in SyncProgress.STAGES) {
            val isCurrent = stage == progress.stage
            val reached = isCurrent || stage.ordinal < progress.stage.ordinal
            val frac = when {
                !reached -> 0f
                isCurrent -> progress.stageFraction
                else -> 1f
            }
            StageBar3D(
                label = stageTitle(stage),
                stageFraction = frac,
                detail = if (isCurrent && progress.total > 0) "${progress.done}/${progress.total}"
                else if (reached) "✓" else "—",
                active = isCurrent,
                reached = reached && !isCurrent,
            )
        }
    }
}

@Composable
fun stageTitle(stage: SyncProgress.Stage): String = when (stage) {
    SyncProgress.Stage.IDLE -> "آماده"
    SyncProgress.Stage.FETCH -> "دریافت منابع"
    SyncProgress.Stage.PARSE -> "خواندن کانفیگ‌ها"
    SyncProgress.Stage.PROBE -> "آزمایش سرورها"
    SyncProgress.Stage.SCAN -> "بررسی پروکسی‌ها"
    SyncProgress.Stage.RANK -> "رتبه‌بندی نهایی"
}

/**
 * The confirmation the settings sheet promises: «تنظیمات ذخیره شد». A toast-shaped card that slides
 * up over the sheet's foot and retires itself — inside a ModalBottomSheet a platform Toast would
 * hide behind the scrim, so the message lives in the composition it belongs to.
 */
@Composable
fun MeelanoSaveToast(visible: Boolean, message: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
        modifier = modifier,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        Brush.verticalGradient(listOf(p.accent, p.accentDeep)),
                        RoundedCornerShape(999.dp),
                    )
                    .border(1.dp, p.accent.copy(alpha = 0.55f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text("✓", fontSize = 13.sp, color = p.accentInk, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(
                    message,
                    fontSize = 13.sp,
                    color = p.accentInk,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
