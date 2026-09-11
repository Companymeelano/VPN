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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border

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
                // the mark sits in a lit well, exactly like the launcher tile: one object, two surfaces
                Box(
                    Modifier
                        .size(96.dp)
                        .shadow(22.dp, RoundedCornerShape(26.dp), clip = false, ambientColor = Meelano.Accent, spotColor = Meelano.Accent)
                        .clip(RoundedCornerShape(26.dp))
                        .background(
                            Brush.verticalGradient(listOf(Meelano.SurfaceHigh, Meelano.Well)),
                            RoundedCornerShape(26.dp),
                        )
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0.10f), Color.Transparent, Color.Black.copy(alpha = 0.22f)),
                            ),
                            RoundedCornerShape(26.dp),
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.13f), RoundedCornerShape(26.dp)),
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
                MeelanoButton(
                    label = stringResource(R.string.ob_done),
                    onClick = {
                        // if the user skipped battery, still arm the in-app watchdog: it is the only
                        // keep-alive that works on OEMs that ignore the battery dialog entirely
                        if (!batteryOk) runCatching { KeepAlive.enableWatchdog(ctx) }
                        onDone()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    size = BtnSize.Large,
                    tone = BtnTone.Primary,
                    iconRes = R.drawable.ic_shield,
                    fill = true,
                )
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
            .shadow(
                if (open) 12.dp else 0.dp,
                RoundedCornerShape(18.dp),
                clip = false,
                ambientColor = Color.Black,
                spotColor = if (open) Meelano.Accent else Color.Black,
            )
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.verticalGradient(
                    if (open) {
                        listOf(Meelano.SurfaceHigh, Meelano.Surface)
                    } else {
                        listOf(Meelano.Surface.copy(alpha = 0.5f), Meelano.Surface.copy(alpha = 0.3f))
                    },
                ),
                RoundedCornerShape(18.dp),
            )
            .background(
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = if (open) 0.07f else 0f), Color.Transparent),
                ),
                RoundedCornerShape(18.dp),
            )
            .border(
                1.dp,
                when {
                    done -> Meelano.Accent.copy(alpha = 0.34f)
                    open -> Color.White.copy(alpha = 0.16f)
                    else -> Color.White.copy(alpha = 0.07f)
                },
                RoundedCornerShape(18.dp),
            )
            .alpha(if (open || !done) 1f else 0.75f)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (done) {
                            Brush.verticalGradient(listOf(Meelano.Accent, Meelano.AccentDeep))
                        } else {
                            Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0.10f), Color.Black.copy(alpha = 0.20f)),
                            )
                        },
                        RoundedCornerShape(12.dp),
                    )
                    .border(
                        1.dp,
                        if (done) Color.White.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.12f),
                        RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // a drawn check, not the "✓" character: the glyph is missing from some system fonts and
                // lands as a box on exactly the OEMs that already break emoji flags
                if (done) {
                    Icon(
                        painterResource(R.drawable.ic_check), null,
                        Modifier.size(13.dp), tint = Meelano.AccentInk,
                    )
                } else {
                    Text(
                        "$index",
                        fontSize = 11.sp,
                        color = Meelano.Muted,
                        fontWeight = FontWeight.Bold,
                        style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                    )
                }
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
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                MeelanoButton(
                    label = action,
                    onClick = onClick,
                    size = BtnSize.Medium,
                    tone = if (index == 1) BtnTone.Primary else BtnTone.Tonal,
                    iconRes = if (index == 1) R.drawable.ic_shield else R.drawable.ic_bolt,
                )
                if (done) {
                    MeelanoButton(
                        label = "انجام شد",
                        onClick = onClick,
                        size = BtnSize.Small,
                        tone = BtnTone.Ghost,
                        iconRes = R.drawable.ic_check,
                    )
                }
            }
        }
    }
}
