package ir.meelano.vpn.ui

import ir.meelano.vpn.ui.theme.LocalPalette

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
import ir.meelano.vpn.ui.theme.MeelanoType
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
    val p = LocalPalette.current
    val ctx = LocalContext.current
    // which step is open: 1 = VPN consent, 2 = battery. A step that is already granted opens nothing.
    var vpnGranted by remember { mutableStateOf(runCatching { VpnService.prepare(ctx) == null }.getOrDefault(false)) }
    var batteryOk by remember { mutableStateOf(KeepAlive.isIgnoringBatteryOptimizations(ctx)) }
    var step by remember { mutableStateOf(if (vpnGranted) 2 else 1) }
    // The consent sheets launch as separate activities: poll ONCE per ON_RESUME, which fires exactly
    // when the user comes back from them. The old "re-check right after startActivity" only ever ran
    // before the user had even seen the dialog, so a granted step never advanced.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                vpnGranted = runCatching { VpnService.prepare(ctx) == null }.getOrDefault(false)
                batteryOk = KeepAlive.isIgnoringBatteryOptimizations(ctx)
                if (vpnGranted && step == 1) step = 2
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Dialog(
        onDismissRequest = { onDone() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(p.bg, if (p.isDark) Color(0xFF070B10) else p.surfaceHigh)))
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
                        .shadow(22.dp, RoundedCornerShape(26.dp), clip = false, ambientColor = p.accent, spotColor = p.accent)
                        .clip(RoundedCornerShape(26.dp))
                        .background(
                            Brush.verticalGradient(listOf(p.surfaceHigh, p.well)),
                            RoundedCornerShape(26.dp),
                        )
                        .background(
                            Brush.verticalGradient(
                                listOf(p.tint(0.10f), Color.Transparent, p.shade(0.22f)),
                            ),
                            RoundedCornerShape(26.dp),
                        )
                        .border(1.dp, p.tint(0.13f), RoundedCornerShape(26.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_launcher_monochrome), null,
                        Modifier.size(58.dp), tint = p.accent,
                    )
                }

                Spacer(Modifier.height(22.dp))
                Text(
                    stringResource(R.string.ob_title),
                    fontSize = 24.sp, fontWeight = FontWeight.Bold, color = p.text, letterSpacing = (-0.4).sp,
                )
                Spacer(Modifier.height(28.dp))

    // Launch through ActivityResult, not raw startActivity: the launcher carries the result back even
    // when the system hand build pauses/resumes differently, and - the failure class that made this
    // button look dead on some OEMs - a thrown ActivityNotFoundException is CAUGHT and shown instead
    // of swallowed. `ctx.startActivity` silently eating the exception left the card looking fine
    // while nothing had happened.
    var launchError by remember { mutableStateOf<String?>(null) }
    val vpnLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) {
        vpnGranted = runCatching { VpnService.prepare(ctx) == null }.getOrDefault(false)
        if (vpnGranted && step == 1) step = 2
    }
    val batteryLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) {
        batteryOk = KeepAlive.isIgnoringBatteryOptimizations(ctx)
    }

    StepCard(
        index = 1,
        current = step,
        done = vpnGranted,
        title = stringResource(R.string.ob_perm_title),
        body = stringResource(R.string.ob_perm_body),
        action = stringResource(R.string.ob_perm_title),
        error = if (step == 1) launchError else null,
    ) {
        launchError = null
        val i = runCatching { VpnService.prepare(ctx) }.getOrNull()
        if (i == null) {
            vpnGranted = true
            step = 2
        } else {
            runCatching { vpnLauncher.launch(i) }
                .onFailure { launchError = it.message ?: it.javaClass.simpleName }
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
        error = if (step == 2) launchError else null,
    ) {
        launchError = null
        val intent = KeepAlive.batteryOptimizationIntent(ctx)
        if (intent != null) {
            runCatching { batteryLauncher.launch(intent) }
                .onFailure { launchError = it.message ?: it.javaClass.simpleName }
            batteryOk = KeepAlive.isIgnoringBatteryOptimizations(ctx)
        } else {
            batteryOk = true                       // already exempt - nothing to open
        }
    }

                Spacer(Modifier.weight(1f))

                Text(
                    "بعداً هم می‌شود از تنظیمات عوضشان کرد.",
                    fontSize = 11.5.sp, color = p.faint, textAlign = TextAlign.Center,
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

                Spacer(Modifier.height(18.dp))
                BrandFooter()
                Spacer(Modifier.height(14.dp))
            }
        }
    }
}

/**
 * The studio signature. One mint hairline that fades at both ends, the M•A monogram, and the credit
 * in brand green - small enough to be a footer, deliberate enough to be read. Kept as its own
 * composable so Home can reuse the exact same mark (same signature on every door of the building).
 */
@Composable
internal fun BrandFooter() {
    val p = LocalPalette.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth(0.55f)
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, p.accent.copy(alpha = 0.35f), Color.Transparent),
                    ),
                ),
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.ic_launcher_monochrome), null,
                Modifier.size(15.dp), tint = p.accent,
            )
            Spacer(Modifier.size(7.dp))
            Text(
                "Meelano Studio Design",
                color = p.accent,
                fontFamily = MeelanoType.Sans,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                letterSpacing = 1.4.sp,
            )
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
    error: String? = null,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    val open = current == index
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(
                if (open) 12.dp else 0.dp,
                RoundedCornerShape(18.dp),
                clip = false,
                ambientColor = p.shade(0.55f),
                spotColor = if (open) p.accent else Color.Black,
            )
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.verticalGradient(
                    if (open) {
                        listOf(p.surfaceHigh, p.surface)
                    } else {
                        listOf(p.surface.copy(alpha = 0.5f), p.surface.copy(alpha = 0.3f))
                    },
                ),
                RoundedCornerShape(18.dp),
            )
            .background(
                Brush.verticalGradient(
                    listOf(p.tint(if (open) 0.07f else 0f), Color.Transparent),
                ),
                RoundedCornerShape(18.dp),
            )
            .border(
                1.dp,
                // the open step wears the brand ring: a mint hairline running across the top edge,
                // fading before it looks like a banner. Done cards only echo it faintly.
                if (open && !done) {
                    Brush.verticalGradient(
                        listOf(p.accent.copy(alpha = 0.55f), p.tint(0.12f)),
                    )
                } else {
                    Brush.verticalGradient(
                        listOf(if (done) p.accent.copy(alpha = 0.34f) else p.tint(0.07f), p.tint(0.05f)),
                    )
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
                            Brush.verticalGradient(listOf(p.accent, p.accentDeep))
                        } else {
                            Brush.verticalGradient(
                                listOf(p.tint(0.10f), p.shade(0.20f)),
                            )
                        },
                        RoundedCornerShape(12.dp),
                    )
                    .border(
                        1.dp,
                        if (done) p.tint(0.30f) else p.tint(0.12f),
                        RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // a drawn check, not the "✓" character: the glyph is missing from some system fonts and
                // lands as a box on exactly the OEMs that already break emoji flags
                if (done) {
                    Icon(
                        painterResource(R.drawable.ic_check), null,
                        Modifier.size(13.dp), tint = p.accentInk,
                    )
                } else {
                    Text(
                        "$index",
                        fontSize = 11.sp,
                        color = if (open) p.accent else p.muted,
                        fontWeight = FontWeight.Bold,
                        fontFamily = MeelanoType.Sans,
                        style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                    )
                }
            }
            Spacer(Modifier.size(10.dp))
            Text(
                title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = if (open || !done) p.text else p.muted,
                modifier = Modifier.weight(1f),
            )
        }
        if (open) {
            Spacer(Modifier.height(8.dp))
            Text(body, fontSize = 12.5.sp, color = p.muted, lineHeight = 20.sp)
            if (error != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.ob_launch_fail, error),
                    fontSize = 11.sp, color = p.danger, lineHeight = 16.sp,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MeelanoButton(
                    label = action,
                    onClick = onClick,
                    size = BtnSize.Large,
                    tone = if (index == 1) BtnTone.Primary else BtnTone.Tonal,
                    iconRes = if (index == 1) R.drawable.ic_shield else R.drawable.ic_bolt,
                    fill = true,
                    modifier = Modifier.weight(1f),
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
