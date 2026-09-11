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
                        .size(34.dp)
                        .clip(RoundedCornerShape(17.dp))
                        .background(Meelano.Accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Meelano.Accent))
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
                        label = if (state is UpdateManager.State.Checking) "…" else stringResource(R.string.upd_now),
                        onClick = { info?.let { vm.updates.downloadAndInstall(it) } },
                    )
                    if (info != null && !info.mandatory) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                stringResource(R.string.upd_wifi),
                                fontSize = 12.sp, color = Meelano.Muted,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        // "وقتی Wi-Fi رسید" is not a snooze: autoUpdate already downloads
                                        // on an unmetered network, so dismissing IS the promise being kept
                                        onDismiss()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
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
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(color))
        Spacer(Modifier.size(10.dp))
        Text(text, fontSize = 12.5.sp, color = Meelano.Text, modifier = Modifier.weight(1f), lineHeight = 18.sp)
        if (action != null) {
            Text(
                action, fontSize = 12.sp, color = color, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun PrimaryRow(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .height(50.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Meelano.Accent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Meelano.AccentInk, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}
