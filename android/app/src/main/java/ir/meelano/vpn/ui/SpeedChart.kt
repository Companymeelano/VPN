package ir.meelano.vpn.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ir.meelano.vpn.R
import ir.meelano.vpn.ui.theme.LocalPalette
import ir.meelano.vpn.vpn.TrafficTrace

/**
 * Throughput history with event markers.
 *
 * The chart is not decoration. It is the only place where "it got slow" can be told apart from "the
 * tunnel dropped and came back", and that difference is exactly what users currently guess wrong. It
 * reads the same samples that drive the notification text (`TrafficTrace`, filled once a second in
 * MeelanoVpnService), so the number above and the line below cannot disagree - which is the whole reason
 * this component takes a trace instead of fetching counters itself.
 *
 * Rules, with the reason attached:
 *  - two polylines, 2dp down / 1.5dp up. **No gradient fill** under the line: on the light palette a
 *    translucent green smear reads as a print defect and hides the baseline it sits on.
 *  - segments are drawn as lines, not as one `Path` memoised outside the canvas: the size is only known
 *    inside the draw lambda, and normalising per sample is cheaper to read than a Transform.
 *  - a marker is a 1.5dp vertical rule in the warn colour, and what it means lives in the legend row -
 *    never as text inside the plot, because Persian labels at that height collide with the line.
 *  - disconnected = dimmed, not hidden. The minute before a drop is precisely what a user wants to look
 *    at, and an empty box teaches them the app forgets.
 *  - the vertical scale has a floor ([TrafficTrace.peak]), so an idle session does not turn 1 KB into a
 *    mountain and the plot does not auto-zoom on noise.
 */
@Composable
fun SpeedChart(
    trace: TrafficTrace,
    live: Boolean,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val dim = if (live) 1f else 0.45f
    val peak = trace.peak()
    val events = trace.marks.size

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(p.well.copy(alpha = if (live) 0.55f else 0.3f))
            .padding(horizontal = 13.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.chart_title),
                style = MaterialTheme.typography.labelSmall,
                color = p.muted,
            )
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.chart_peak) + " " + humanRate(peak.toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = p.faint,
            )
        }

        Canvas(Modifier.fillMaxWidth().height(60.dp).alpha(dim)) {
            val n = trace.down.size
            val h = size.height
            val w = size.width
            val pad = 5.dp.toPx()
            // two dashed rules: enough to judge height, quiet enough not to compete with the data
            listOf(0.34f, 0.67f).forEach { f ->
                drawLine(
                    color = p.hairline,
                    start = Offset(0f, h * f),
                    end = Offset(w, h * f),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 6f), 0f),
                )
            }
            if (n < 2) {
                // an honest empty state: a flat baseline. Not a spinner, not a fake mountain.
                drawLine(p.line, Offset(0f, h - pad), Offset(w, h - pad), 1.5.dp.toPx(), cap = StrokeCap.Round)
                return@Canvas
            }
            val step = w / (n - 1).toFloat()
            val usable = h - 2f * pad
            val yOf = { v: Float -> h - pad - (v / peak).coerceIn(0f, 1f) * usable }
            var i = 1
            while (i < n) {
                drawLine(
                    color = p.accent,
                    start = Offset(step * (i - 1), yOf(trace.down[i - 1])),
                    end = Offset(step * i, yOf(trace.down[i])),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = p.info.copy(alpha = 0.8f),
                    start = Offset(step * (i - 1), yOf(trace.up[i - 1])),
                    end = Offset(step * i, yOf(trace.up[i])),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                i++
            }
            trace.marks.forEach { m ->
                if (m in 0 until n) {
                    val x = step * m
                    drawLine(
                        color = p.warn.copy(alpha = 0.85f),
                        start = Offset(x, pad * 0.3f),
                        end = Offset(x, h - pad * 0.3f),
                        strokeWidth = 1.5.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendDot(p.accent, stringResource(R.string.chart_down))
            LegendDot(p.info, stringResource(R.string.chart_up))
            if (events > 0) {
                LegendDot(p.warn, stringResource(R.string.chart_events))
            } else if (n < 2) {
                Text(
                    stringResource(R.string.chart_empty),
                    style = MaterialTheme.typography.labelSmall,
                    color = p.faint,
                )
            }
        }
    }
}

/**
 * A dot and a word. Deliberately not an icon: a legend marker carries no affordance, and an icon implies
 * one (Controls.kt draws the line between "this opens something" and "this does something" the same way).
 */
@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            " " + label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
