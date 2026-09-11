package ir.meelano.vpn.ui

import android.content.Intent
import android.net.VpnService
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ir.meelano.vpn.R
import ir.meelano.vpn.keepalive.KeepAlive
import ir.meelano.vpn.ui.theme.Meelano

/**
 * Two steps, one screen, and the app is usable if the user refuses either.
 *
 * The order is not decoration: VPN consent is unavoidable and blocks the first connect, so it goes
 * first while the user is still patient. Battery optimisation is the one that actually keeps the
 * tunnel alive after the app is swiped away — the exact complaint in the brief — so it gets its own
 * step instead of a footnote on the last slide of a 4-page carousel nobody reads.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    // which step is open: 1 = VPN consent, 2 = battery. A step that is already granted opens nothing.
    var vpnGranted by remember { mutableStateOf(runCatching { VpnService.prepare(ctx) == null }.getOrDefault(false)) }
    var batteryOk by remember { mutableStateOf(KeepAlive.isIgnoringBatteryOptimizations(ctx)) }
    var step by remember { mutableStateOf(if (vpnGranted) 2 else 1) }

    Dialog(
        onDismissRequest = { onDone() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Meelano.Bg, Color(0xFF070B10))))
                .statusBarsPadding()
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(46.dp))

                // the brand mark, drawn once, at a size that lets the ring's geometry read
                Box(
                    Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(Meelano.Surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_launcher_monochrome), null,
                        Modifier.size(58.dp), tint = Meelano.Accent,
                    )
                }

                Spacer(Modifier.height(22.dp))
                Text(
                    stringResource(R.string.ob_title),
                    fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Meelano.Text, letterSpacing = (-0.4).sp,
                )
                Spacer(Modifier.height(28.dp))

                StepCard(
                    index = 1,
                    current = step,
                    done = vpnGranted,
                    title = stringResource(R.string.ob_perm_title),
                    body = stringResource(R.string.ob_perm_body),
                    action = stringResource(R.string.ob_perm_title),
                ) {
                    val i = runCatching { VpnService.prepare(ctx) }.getOrNull()
                    if (i == null) {
                        vpnGranted = true
                        step = 2
                    } else {
                        runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        // returning from the system sheet re-checks it; no polling loop
                        vpnGranted = runCatching { VpnService.prepare(ctx) == null }.getOrDefault(false)
                        if (vpnGranted) step = 2
                    }
                }

                Spacer(Modifier.height(12.dp))

                StepCard(
                    index = 2,
                    current = step,
                    done = batteryOk,
                    title = stringResource(R.string.ob_battery_title),
                    body = stringResource(R.string.ob_battery_body),
                    action = stringResource(R.string.ob_battery_title),
                ) {
                    val intent = KeepAlive.batteryOptimizationIntent(ctx)
                    if (intent != null) runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    batteryOk = KeepAlive.isIgnoringBatteryOptimizations(ctx)
                }

                Spacer(Modifier.weight(1f))

                Text(
                    "بعداً هم می‌شود از تنظیمات عوضشان کرد.",
                    fontSize = 11.5.sp, color = Meelano.MutedFaint, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Meelano.Accent)
                        .clickable {
                            // if the user skipped battery, still arm the in-app watchdog: it is the
                            // only keep-alive that works on OEMs that ignore the battery dialog entirely
                            if (!batteryOk) runCatching { KeepAlive.enableWatchdog(ctx) }
                            onDone()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.ob_done),
                        color = Meelano.AccentInk, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

/**
 * A step row, not a wizard page. It shows its own state (قفل/باز) so the user knows what is left,
 * and the finished one collapses to one line — attention should stay on the step still open.
 */
@Composable
private fun StepCard(
    index: Int,
    current: Int,
    done: Boolean,
    title: String,
    body: String,
    action: String,
    onClick: () -> Unit,
) {
    val open = current == index
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (open) Meelano.Surface else Meelano.Surface.copy(alpha = 0.45f))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (done) Meelano.Accent else Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (done) "✓" else "$index",
                    fontSize = 11.sp,
                    color = if (done) Meelano.AccentInk else Meelano.Muted,
                    fontWeight = FontWeight.Bold,
                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                )
            }
            Spacer(Modifier.size(10.dp))
            Text(
                title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = if (open || !done) Meelano.Text else Meelano.Muted,
                modifier = Modifier.weight(1f),
            )
        }
        if (open) {
            Spacer(Modifier.height(8.dp))
            Text(body, fontSize = 12.5.sp, color = Meelano.Muted, lineHeight = 20.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Meelano.Accent.copy(alpha = 0.14f))
                        .clickable(onClick = onClick)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(action, color = Meelano.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
