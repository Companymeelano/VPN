package ir.meelano.vpn.ui

import ir.meelano.vpn.ui.theme.LocalPalette

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.meelano.vpn.R
import ir.meelano.vpn.update.UpdateInfo
import ir.meelano.vpn.update.UpdateManager
import ir.meelano.vpn.data.AppSettings

/**
 * The update flow as a *surface you can watch*, never a dialog that steals the screen on launch —
 * and, since 2.8.0, as the showpiece the user asked for: a 3D hero card for the «نسخه‌ی جدید آمد»
 * moment, and the Progress3D dial with the *real* byte percentage while the file downloads, is
 * saved to the phone and handed to the installer.
 *
 * Why the copy stays honest: Android will not let an app install itself silently (only a Device
 * Owner can, and Google Play forbids that for a consumer VPN), so the promise is "we fetch, verify
 * and save it, the installer opens itself, you tap تأیید once". Saying «خودکار نصب می‌شود» and then
 * showing a system prompt is how you teach people to distrust the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheet(vm: VpnViewModel, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val state by vm.updateState.collectAsState()
    val sheet = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = p.surface,
        contentColor = p.text,
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Box(
                Modifier
                    .padding(top = 10.dp)
                    .size(width = 32.dp, height = 3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(p.tint(0.18f))
                    .align(Alignment.CenterHorizontally),
            )

            val stateInfo = when (state) {
                is UpdateManager.State.Available -> (state as UpdateManager.State.Available).info
                is UpdateManager.State.ReadyToInstall -> (state as UpdateManager.State.ReadyToInstall).info
                is UpdateManager.State.BlockedNeedPermission -> (state as UpdateManager.State.BlockedNeedPermission).info
                else -> null
            }
            // Downloading/Verifying carry no UpdateInfo — remember the last one so the dial keeps
            // showing real bytes/total through the whole ride instead of falling back to zeroes.
            var lastInfo by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<UpdateInfo?>(null) }
            androidx.compose.runtime.LaunchedEffect(state) {
                if (stateInfo != null) lastInfo = stateInfo
            }
            val info = stateInfo ?: lastInfo

            // ── the hero: «نسخه‌ی جدید آمد» on a glowing 3D slab ─────────────────────────────
            HeroCard(version = info?.versionName, size = info?.sizeBytes ?: 0L)

            if (info != null && info.changelog.isNotBlank()) {
                Column(
                    Modifier
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(p.well)
                        .padding(12.dp)
                        .height(110.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    info.changelog.lines().filter { it.isNotBlank() }.forEach {
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text("·", color = p.accent, fontSize = 12.sp)
                            Spacer(Modifier.size(6.dp))
                            Text(it.trim(), fontSize = 12.sp, color = p.muted, lineHeight = 19.sp)
                        }
                    }
                }
            }

            // «لینک‌های جدید در برنامه»: the direct asset link, visible before the tap.
            if (info != null && info.url.isNotBlank()) {
                LinkRow(url = info.url)
            }

            when (state) {
                is UpdateManager.State.Downloading -> {
                    val d = state as UpdateManager.State.Downloading
                    DownloadDial(percent = d.percent, bytes = d.bytes, total = d.totalBytes, speed = d.bytesPerSec)
                }
                is UpdateManager.State.Verifying -> {
                    DownloadDial(
                        percent = 100,
                        bytes = info?.sizeBytes ?: 0L,
                        total = info?.sizeBytes ?: 0L,
                        speed = 0L,
                        caption = stringResource(R.string.upd_saved_verifying),
                    )
                }
                is UpdateManager.State.Failed -> Notice(
                    text = stringResource(R.string.upd_corrupt),
                    color = p.danger,
                    action = stringResource(R.string.retry),
                    onAction = { vm.updates.checkAsync() },
                )
                is UpdateManager.State.BlockedNeedPermission -> {
                    val file = (state as UpdateManager.State.BlockedNeedPermission).file
                    SavedStrip(file = file)
                    Notice(
                        text = stringResource(R.string.upd_noperm),
                        color = p.warn,
                        action = stringResource(R.string.upd_open_settings),
                        onAction = { runCatching { ctx.startActivity(vm.updates.permissionIntent()) } },
                    )
                    PrimaryRow(
                        label = stringResource(R.string.upd_now),
                        onClick = {
                            runCatching {
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    ctx, "${ctx.packageName}.fileprovider", file,
                                )
                                val i = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "application/vnd.android.package-archive")
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                ctx.startActivity(i)
                            }
                        },
                    )
                }
                is UpdateManager.State.ReadyToInstall -> {
                    val file = (state as UpdateManager.State.ReadyToInstall).file
                    SavedStrip(file = file)
                    Notice(
                        text = stringResource(R.string.upd_ready),
                        color = p.accent,
                        action = null,
                        onAction = {},
                    )
                    PrimaryRow(
                        label = stringResource(R.string.upd_now),
                        onClick = { runCatching { vm.updates.launchInstaller(file) } },
                    )
                }
                else -> {
                    // Available / Checking: the CTA is the download itself
                    PrimaryRow(
                        label = stringResource(R.string.upd_now),
                        onClick = { info?.let { vm.updates.downloadAndInstall(it) } },
                        loading = state is UpdateManager.State.Checking,
                        enabled = info != null,
                    )
                    if (info != null && !info.mandatory) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            MeelanoButton(
                                label = stringResource(R.string.upd_wifi),
                                // «وقتی Wi-Fi رسید» is not a snooze: autoUpdate already downloads on
                                // an unmetered network, so dismissing IS the promise being kept
                                onClick = { onDismiss() },
                                tone = BtnTone.Ghost,
                                size = BtnSize.Small,
                            )
                        }
                    } else if (info != null) {
                        Text(
                            stringResource(R.string.upd_mandatory),
                            fontSize = 11.sp, color = p.warn,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            Text(
                stringResource(R.string.upd_honesty),
                fontSize = 10.5.sp, color = p.faint, lineHeight = 16.sp,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp),
            )
        }
    }
}

/**
 * The «بسیار جذاب و خیره‌کننده» card: a raised slab lit from above, an accent halo that breathes,
 * a version medallion, and — the part that matters — one clear sentence about what just happened.
 * The halo is the same radial recipe as the control kit's, so the whole app stays one object set.
 */
@Composable
private fun HeroCard(version: String?, size: Long) {
    val p = LocalPalette.current
    val reduced = AppSettings.reducedMotion
    val breath = rememberInfiniteTransition(label = "updHero")
    val glow by breath.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(if (reduced) 0 else 1600, easing = LinearEasing),
            RepeatMode.Reverse,
        ),
        label = "updGlow",
    )
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    // halo: the update is the one moment that deserves a light source
                    drawRect(
                        brush = Brush.radialGradient(
                            0f to p.accent.copy(alpha = 0.30f * glow),
                            1f to Color.Transparent,
                            center = Offset(size.width / 2f, size.height / 2f),
                            radius = maxOf(size.width, size.height) * 0.75f,
                        ),
                    )
                }
                .meeExtruded(corner = 20.dp, depth = 6.dp, pressed = false, enabled = true)
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        listOf(p.accent.copy(alpha = 0.22f), p.surfaceHigh.copy(alpha = 0.98f)),
                    ),
                    shape,
                )
                .border(1.dp, p.accent.copy(alpha = 0.42f), shape)
                .padding(horizontal = 18.dp, vertical = 18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // medallion
                Box(
                    Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(17.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(p.accent.copy(alpha = 0.34f), p.accentDeep.copy(alpha = 0.16f)),
                            ),
                            RoundedCornerShape(17.dp),
                        )
                        .border(1.dp, p.accent.copy(alpha = 0.5f), RoundedCornerShape(17.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_download), null,
                        Modifier.size(26.dp), tint = p.accent,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.upd_hero_title),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = p.text,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (version != null) {
                            stringResource(R.string.upd_hero_body, version, humanSize(size))
                        } else stringResource(R.string.upd_hero_checking),
                        fontSize = 11.5.sp,
                        color = p.muted,
                        lineHeight = 17.sp,
                        style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                    )
                }
            }
        }
    }
}

/** The real-numbers download surface: 3D dial + exact percent + MB counters + measured speed. */
@Composable
private fun DownloadDial(percent: Int, bytes: Long, total: Long, speed: Long, caption: String = "") {
    val p = LocalPalette.current
    val clamped = percent.coerceIn(0, 100)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StageDial3D(
            overallFraction = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else clamped / 100f,
            percentText = "$clamped%",
            active = clamped < 100,
            size = 128.dp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            caption.ifBlank { stringResource(R.string.upd_downloading, clamped) },
            fontSize = 12.5.sp,
            color = p.text,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            buildString {
                append(humanSize(bytes))
                if (total > 0) append(" از ").append(humanSize(total))
                if (speed > 0) append("  ·  ").append(humanSize(speed)).append("/s")
            },
            fontSize = 11.sp,
            color = p.faint,
            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
        )
    }
}

/** «در گوشی ذخیره شد» — the state the user asked to see at 100 %: the file exists on the phone. */
@Composable
private fun SavedStrip(file: java.io.File) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.verticalGradient(listOf(p.accent.copy(alpha = 0.16f), p.accent.copy(alpha = 0.05f))),
                RoundedCornerShape(12.dp),
            )
            .border(1.dp, p.accent.copy(alpha = 0.34f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("✓", color = p.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(stringResource(R.string.upd_saved), fontSize = 12.sp, color = p.text, fontWeight = FontWeight.Medium)
            Text(
                file.name,
                fontSize = 10.sp, color = p.faint, maxLines = 1,
                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
            )
        }
    }
}

/** The direct asset link, in the app, before the tap — copyable by the user from a screenshot. */
@Composable
private fun LinkRow(url: String) {
    val p = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(p.well)
            .border(1.dp, p.tint(0.10f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(stringResource(R.string.upd_link_title), fontSize = 10.sp, color = p.faint)
        Spacer(Modifier.height(3.dp))
        Text(
            url,
            fontSize = 10.5.sp, color = p.muted, maxLines = 2, lineHeight = 15.sp,
            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
        )
    }
}

@Composable
private fun Notice(text: String, color: Color, action: String?, onAction: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(shape)
            .background(
                Brush.verticalGradient(listOf(color.copy(alpha = 0.16f), color.copy(alpha = 0.05f))),
                shape,
            )
            .background(
                Brush.verticalGradient(listOf(p.tint(0.06f), p.shade(0.10f))),
                shape,
            )
            .border(1.dp, color.copy(alpha = 0.34f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // the dot carries the colour, so the strip can stay quiet; the glow is the same halo the
        // buttons use, which is why a notice and a button look like they came from one object set
        Box(
            Modifier
                .size(17.dp)
                .drawBehind {
                    drawRect(
                        brush = Brush.radialGradient(
                            0f to color.copy(alpha = 0.6f),
                            1f to Color.Transparent,
                            center = Offset(size.width / 2f, size.height / 2f),
                            radius = size.maxDimension / 2f,
                        ),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(color))
        }
        Spacer(Modifier.size(8.dp))
        Text(text, fontSize = 12.5.sp, color = p.text, modifier = Modifier.weight(1f), lineHeight = 18.sp)
        if (action != null) {
            Spacer(Modifier.size(6.dp))
            MeelanoButton(
                label = action,
                onClick = onAction,
                tone = BtnTone.Tonal,
                size = BtnSize.Small,
            )
        }
    }
}

@Composable
private fun PrimaryRow(
    label: String,
    onClick: () -> Unit,
    loading: Boolean = false,
    enabled: Boolean = true,
) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        MeelanoButton(
            label = label,
            onClick = onClick,
            fill = true,
            size = BtnSize.Large,
            tone = BtnTone.Primary,
            iconRes = R.drawable.ic_download,
            loading = loading,
            enabled = enabled,
        )
    }
}
