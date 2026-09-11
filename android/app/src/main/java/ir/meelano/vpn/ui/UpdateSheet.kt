package ir.meelano.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import ir.meelano.vpn.R
import ir.meelano.vpn.update.UpdateManager
import ir.meelano.vpn.ui.theme.Meelano
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset

/**
 * The update flow as a *surface you can watch*, never a dialog that steals the screen on launch.
 *
 * Why the copy is written this way: Android will not let an app install itself silently (only a
 * Device Owner can, and Google Play forbids that for a consumer VPN), so the honest promise is
 * "we fetch and verify it, you tap تأیید once". Saying "خودکار نصب می‌شود" and then showing a
 * system prompt is how you teach people to distrust the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheet(vm: VpnViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val state by vm.updateState.collectAsState()
    val sheet = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = Meelano.Surface,
        contentColor = Meelano.Text,
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Box(
                Modifier
                    .padding(top = 10.dp)
                    .size(width = 32.dp, height = 3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.18f))
                    .align(Alignment.CenterHorizontally)
            )

            val info = when (state) {
                is UpdateManager.State.Available -> (state as UpdateManager.State.Available).info
                is UpdateManager.State.ReadyToInstall -> (state as UpdateManager.State.ReadyToInstall).info
                is UpdateManager.State.BlockedNeedPermission -> (state as UpdateManager.State.BlockedNeedPermission).info
                else -> null
            }

            Row(
                Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(Meelano.Accent.copy(alpha = 0.22f), Meelano.Accent.copy(alpha = 0.06f)),
                            ),
                            RoundedCornerShape(13.dp),
                        )
                        .border(1.dp, Meelano.Accent.copy(alpha = 0.42f), RoundedCornerShape(13.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_download), null,
                        Modifier.size(19.dp), tint = Meelano.Accent,
                    )
                }
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.upd_title), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        info?.let { "v${it.versionName} · ${humanSize(it.sizeBytes)}" } ?: "—",
                        fontSize = 11.sp, color = Meelano.MutedFaint,
                        style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                    )
                }
                Spacer(Modifier.weight(1f))
                MeelanoIconButton(
                    iconRes = R.drawable.ic_close,
                    contentDescription = stringResource(R.string.close),
                    onClick = onDismiss,
                    sizeDp = 38.dp,
                    corner = 12.dp,
                )
            }

            if (info != null && info.changelog.isNotBlank()) {
                Column(
                    Modifier
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Meelano.Well)
                        .padding(12.dp)
                        .height(120.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    info.changelog.lines().filter { it.isNotBlank() }.forEach {
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text("·", color = Meelano.Accent, fontSize = 12.sp)
                            Spacer(Modifier.size(6.dp))
                            Text(it.trim(), fontSize = 12.sp, color = Meelano.Muted, lineHeight = 19.sp)
                        }
                    }
                }
            }

            when (state) {
                is UpdateManager.State.Downloading -> {
                    val p = (state as UpdateManager.State.Downloading).percent
                    ProgressBar(p / 100f, stringResource(R.string.upd_downloading, p))
                }
                is UpdateManager.State.Verifying ->
                    ProgressBar(null, stringResource(R.string.upd_verifying))
                is UpdateManager.State.Failed ->
                    Notice(
                        text = stringResource(R.string.upd_corrupt),
                        color = Meelano.Danger,
                        action = stringResource(R.string.retry),
                        onAction = { vm.updates.checkAsync() },
                    )
                is UpdateManager.State.BlockedNeedPermission -> {
                    val file = (state as UpdateManager.State.BlockedNeedPermission).file
                    Notice(
                        text = stringResource(R.string.upd_noperm),
                        color = Meelano.Warn,
                        action = stringResource(R.string.upd_open_settings),
                        onAction = { runCatching { ctx.startActivity(vm.updates.permissionIntent()) } },
                    )
                    PrimaryRow(
                        label = stringResource(R.string.upd_now),
                        onClick = {
                            runCatching {
                                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
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
                    Notice(
                        text = stringResource(R.string.upd_ready),
                        color = Meelano.Accent,
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
                                // "وقتی Wi-Fi رسید" is not a snooze: autoUpdate already downloads on an
                                // unmetered network, so dismissing IS the promise being kept
                                onClick = { onDismiss() },
                                tone = BtnTone.Ghost,
                                size = BtnSize.Small,
                            )
                        }
                    } else if (info != null) {
                        Text(
                            stringResource(R.string.upd_mandatory),
                            fontSize = 11.sp, color = Meelano.Warn,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            Text(
                "اندروید نصب بی‌صدا را فقط به Device Owner اجازه می‌دهد؛ برای همین یک تأیید دستی لازم است.",
                fontSize = 10.5.sp, color = Meelano.MutedFaint, lineHeight = 16.sp,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp),
            )
        }
    }
}

/** a 3dp bar with a soft accent tip: `null` progress = indeterminate sweep (we never fake a percentage) */
@Composable
private fun ProgressBar(progress: Float?, label: String) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.08f))
        ) {
            if (progress != null) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0.02f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(Meelano.Accent)
                )
            } else {
                Box(
                    Modifier
                        .fillMaxWidth(0.3f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(Meelano.Accent.copy(alpha = 0.7f))
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            label, fontSize = 12.sp, color = Meelano.Muted,
            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
        )
    }
}

@Composable
private fun Notice(text: String, color: Color, action: String?, onAction: () -> Unit) {
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
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.06f), Color.Black.copy(alpha = 0.10f))),
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
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        }
        Spacer(Modifier.size(8.dp))
        Text(text, fontSize = 12.5.sp, color = Meelano.Text, modifier = Modifier.weight(1f), lineHeight = 18.sp)
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
