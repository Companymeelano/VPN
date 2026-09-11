package ir.meelano.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.meelano.vpn.BuildConfig
import ir.meelano.vpn.R
import ir.meelano.vpn.data.AppSettings
import ir.meelano.vpn.keepalive.KeepAlive
import ir.meelano.vpn.ui.theme.Meelano

/**
 * Settings as a sheet, one decision per row, and the two switches that actually matter are at the
 * top. Nothing here is a "danger zone" — the reset row is at the bottom with a plain confirmation,
 * because a private VPN's users lose their config if they tap it by accident.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmReset by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = Meelano.Surface,
        contentColor = Meelano.Text,
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 18.dp),
        ) {
            Box(
                Modifier
                    .padding(top = 10.dp)
                    .size(width = 32.dp, height = 3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.18f))
                    .align(Alignment.CenterHorizontally)
            )
            Text(
                stringResource(R.string.settings),
                fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Meelano.Text,
                modifier = Modifier.padding(start = 18.dp, top = 12.dp, bottom = 4.dp),
            )

            SwitchRow(
                title = stringResource(R.string.set_keepalive),
                body = stringResource(R.string.set_keepalive_body),
                on = AppSettings.smartReconnect,
                onChange = {
                    AppSettings.setSmartReconnect(ctx, it)
                    // the watchdog only exists while it is on; toggling mid-session must take effect now,
                    // not on the next cold start — that gap is how "I turned it on and it still dropped" happens
                    runCatching { if (it) KeepAlive.enableWatchdog(ctx) else KeepAlive.disableWatchdog(ctx) }
                },
            )
            SwitchRow(
                title = stringResource(R.string.set_killswitch),
                body = stringResource(R.string.set_killswitch_body),
                on = AppSettings.killSwitch,
                onChange = { AppSettings.setKillSwitch(ctx, it) },
            )
            SwitchRow(
                title = stringResource(R.string.set_dns),
                body = stringResource(R.string.set_dns_body),
                on = AppSettings.secureDns,
                onChange = { AppSettings.setSecureDns(ctx, it) },
            )

            Divider()

            SwitchRow(
                title = stringResource(R.string.set_update),
                body = stringResource(R.string.set_update_body),
                on = AppSettings.autoUpdate,
                onChange = { AppSettings.setAutoUpdate(ctx, it) },
            )
            SwitchRow(
                title = stringResource(R.string.set_feedback),
                body = stringResource(R.string.set_feedback_body),
                on = AppSettings.feedback,
                onChange = { AppSettings.setFeedback(ctx, it) },
            )
            SwitchRow(
                title = stringResource(R.string.set_reduced),
                body = stringResource(R.string.set_reduced_body),
                on = AppSettings.reducedMotion,
                onChange = { AppSettings.setReducedMotion(ctx, it) },
            )

            Divider()

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.set_theme), fontSize = 14.sp, color = Meelano.Text, modifier = Modifier.weight(1f))
                Segmented(
                    items = listOf(
                        stringResource(R.string.theme_system),
                        stringResource(R.string.theme_dark),
                        stringResource(R.string.theme_light),
                    ),
                    index = AppSettings.themeMode,
                    onIndex = { AppSettings.setThemeMode(ctx, it) },
                )
            }

            Divider()

            Text(
                text = if (confirmReset) stringResource(R.string.set_reset) + " ؟" else stringResource(R.string.set_reset),
                fontSize = 13.sp,
                color = if (confirmReset) Meelano.Danger else Meelano.Muted,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        if (confirmReset) {
                            AppSettings.reset(ctx)
                            confirmReset = false
                            onDismiss()
                        } else confirmReset = true
                    }
                    .padding(vertical = 12.dp),
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.set_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    fontSize = 11.sp, color = Meelano.MutedFaint, modifier = Modifier.weight(1f),
                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                )
            }
        }
    }
}

/**
 * The row is title + one-line body + a switch, 56dp tall, and the *whole row* is the touch target.
 * A switch that is 32dp with a 12dp label gap is the classic 44%-miss target; the row-as-target is
 * how Material's own settings app does it.
 */
@Composable
private fun SwitchRow(title: String, body: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 18.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onChange(!on) }
            .padding(horizontal = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = Meelano.Text, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(body, fontSize = 11.sp, color = Meelano.MutedFaint, maxLines = 2)
        }
        Spacer(Modifier.size(12.dp))
        Switch(
            checked = on,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Meelano.AccentInk,
                checkedTrackColor = Meelano.Accent,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Meelano.Muted,
                uncheckedTrackColor = Color.White.copy(alpha = 0.10f),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp)
            .height(1.dp)
            .background(Meelano.Line)
    )
}
