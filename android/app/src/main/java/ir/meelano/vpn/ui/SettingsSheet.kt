package ir.meelano.vpn.ui

import ir.meelano.vpn.ui.theme.LocalPalette

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.meelano.vpn.BuildConfig
import ir.meelano.vpn.R
import ir.meelano.vpn.data.AppSettings
import ir.meelano.vpn.keepalive.KeepAlive
import ir.meelano.vpn.ui.theme.Meelano

/**
 * Settings as a sheet, one decision per row, and the two switches that actually matter are at the top.
 * Nothing here is a "danger zone" — the reset row is at the bottom inside its own panel with a plain
 * confirmation, because a private VPN's users lose their config if they tap it by accident.
 *
 * Grouping rule: three panels, never a wall of ten rows. A panel is a *promise that rows inside it
 * belong together*, and the gap between panels is the only separator the eye needs — that is why the
 * dividers inside a panel fade out at both ends (a hard full-width line inside a rounded card looks
 * pasted on, and it is the detail that separates "designed" from "styled").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmReset by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = p.surface,
        contentColor = p.text,
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 20.dp),
        ) {
            Box(
                Modifier
                    .padding(top = 10.dp)
                    .size(width = 32.dp, height = 3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(p.tint(0.18f))
                    .align(Alignment.CenterHorizontally)
            )
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.settings),
                    fontSize = 17.sp, fontWeight = FontWeight.Bold, color = p.text,
                )
                Spacer(Modifier.weight(1f))
                MeelanoIconButton(
                    iconRes = R.drawable.ic_close,
                    contentDescription = stringResource(R.string.close),
                    onClick = onDismiss,
                    sizeDp = 38.dp,
                    corner = 12.dp,
                )
            }

            MeelanoPanel(title = stringResource(R.string.group_vpn)) {
                SwitchRow(
                    title = stringResource(R.string.set_keepalive),
                    body = stringResource(R.string.set_keepalive_body),
                    on = AppSettings.smartReconnect,
                    onChange = {
                        AppSettings.setSmartReconnect(ctx, it)
                        // the watchdog only exists while it is on; toggling mid-session must take effect
                        // now, not on the next cold start — that gap is how "I turned it on and it still
                        // dropped" is born
                        runCatching { if (it) KeepAlive.enableWatchdog(ctx) else KeepAlive.disableWatchdog(ctx) }
                    },
                )
                PanelDivider()
                SwitchRow(
                    title = stringResource(R.string.set_killswitch),
                    body = stringResource(R.string.set_killswitch_body),
                    on = AppSettings.killSwitch,
                    onChange = { AppSettings.setKillSwitch(ctx, it) },
                )
                PanelDivider()
                SwitchRow(
                    title = stringResource(R.string.set_dns),
                    body = stringResource(R.string.set_dns_body),
                    on = AppSettings.secureDns,
                    onChange = { AppSettings.setSecureDns(ctx, it) },
                )
            }

            MeelanoPanel(title = stringResource(R.string.group_data)) {
                SwitchRow(
                    title = stringResource(R.string.set_update),
                    body = stringResource(R.string.set_update_body),
                    on = AppSettings.autoUpdate,
                    onChange = { AppSettings.setAutoUpdate(ctx, it) },
                )
                PanelDivider()
                SwitchRow(
                    title = stringResource(R.string.set_feedback),
                    body = stringResource(R.string.set_feedback_body),
                    on = AppSettings.feedback,
                    onChange = { AppSettings.setFeedback(ctx, it) },
                )
            }

            MeelanoPanel(title = stringResource(R.string.group_general)) {
                SwitchRow(
                    title = stringResource(R.string.set_reduced),
                    body = stringResource(R.string.set_reduced_body),
                    on = AppSettings.reducedMotion,
                    onChange = { AppSettings.setReducedMotion(ctx, it) },
                )
                PanelDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        stringResource(R.string.set_theme),
                        fontSize = 14.sp, color = p.text, fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(9.dp))
                    MeelanoSegmented(
                        items = listOf(
                            stringResource(R.string.theme_system),
                            stringResource(R.string.theme_dark),
                            stringResource(R.string.theme_light),
                        ),
                        index = AppSettings.themeMode,
                        onIndex = { AppSettings.setThemeMode(ctx, it) },
                    )
                }
            }

            MeelanoPanel(title = stringResource(R.string.danger_zone)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                    MeelanoButton(
                        label = if (confirmReset) stringResource(R.string.set_reset) + " ؟" else stringResource(R.string.set_reset),
                        onClick = {
                            if (confirmReset) {
                                AppSettings.reset(ctx)
                                confirmReset = false
                                onDismiss()
                            } else confirmReset = true
                        },
                        tone = BtnTone.Danger,
                        size = BtnSize.Medium,
                        fill = true,
                    )
                    if (confirmReset) {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            MeelanoButton(
                                label = "بی‌خیال",
                                onClick = { confirmReset = false },
                                tone = BtnTone.Ghost,
                                size = BtnSize.Small,
                            )
                        }
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.set_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    fontSize = 11.sp, color = p.mutedFaint, modifier = Modifier.weight(1f),
                    style = TextStyle(fontFeatureSettings = "tnum"),
                )
                StateDot(
                    on = AppSettings.smartReconnect,
                    label = if (AppSettings.smartReconnect) "نگه‌دارنده روشن" else "نگه‌دارنده خاموش",
                )
            }
        }
    }
}

/**
 * The row is title + one-line body + a switch, 56dp tall, and the *whole row* is the touch target.
 * A switch that is 32dp with a 12dp label gap is the classic 44%-miss target; the row-as-target is how
 * Material's own settings app does it. The row also lights up when pressed — otherwise tapping the
 * text does visibly nothing at all, and "did that register?" is the most common reason people toggle
 * something twice and turn a feature off by accident.
 */
@Composable
private fun SwitchRow(title: String, body: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val shrink by animateFloatAsState(if (pressed) 0.995f else 1f, label = "rowPress")
    Row(
        Modifier
            .fillMaxWidth()
            .scale(shrink)
            .height(60.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        if (pressed) p.tint(0.06f) else Color.Transparent,
                        if (pressed) p.tint(0.03f) else Color.Transparent,
                    ),
                ),
                RoundedCornerShape(14.dp),
            )
            .clickable(interactionSource = src, indication = null) { onChange(!on) }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 14.sp,
                color = if (on) p.text else p.muted,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(body, fontSize = 11.sp, color = p.mutedFaint, maxLines = 2, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.alpha(if (on) 1f else 0.85f)) { MeelanoSwitch(checked = on, onChange = onChange) }
    }
}
