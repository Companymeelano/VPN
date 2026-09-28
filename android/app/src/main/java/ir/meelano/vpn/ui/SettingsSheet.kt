package ir.meelano.vpn.ui

import ir.meelano.vpn.ui.theme.LocalPalette

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.SolidColor
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
import kotlinx.coroutines.launch

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
fun SettingsSheet(vm: VpnViewModel, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmReset by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(false) }
    // The on-device list has no host to ask "what happened", so the sheet carries the answer: which
    // sources answered, and what was thrown away. Local state only - nothing here survives the sheet.
    val feedNotes by vm.feedNotes.collectAsState()
    // Read once, off the composition: `remember` + a suspend read means the sheet opens without waiting
    // on a disk seek, and the draft is what the user sees from then on.
    var savedVip by remember { mutableStateOf("") }
    // Read once, off the composition: File.length() on the UI thread is jank you can feel.
    var hasLocalVip by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        savedVip = vm.readLocalVip()
        hasLocalVip = runCatching { vm.hasLocalVip() }.getOrDefault(false)
    }
    var vipDraft by remember { mutableStateOf<String?>(null) }
    var subDraft by remember { mutableStateOf<String?>(null) }
    var savedLines by remember { mutableStateOf(-1) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = p.surface,
        contentColor = p.text,
        dragHandle = null,
    ) {
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
        ) {
            // A header + close button. The extra Column is not decoration: Modifier.align is a
            // ColumnScope API, and LazyColumn's item scope is not one - without it this does not compile.
            item {
                Column(Modifier.fillMaxWidth()) {
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
                }
            }

            // The VPN panel - the two switches that matter most, first.
            item {
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

            }

            // The resistance panel is a first-class group, not a debug drawer: in this country "the VPN
            // doesn't work" is a transport problem nine times out of ten, and the four dials that fix it
            // have until now been buried in a fork's config file. The defaults are already right for
            // Iran (docs/ANTI-BLOCK.md §۴) - this panel exists for the day they are not.
            item {
                MeelanoPanel(title = stringResource(R.string.group_resist)) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(
                            stringResource(R.string.set_regime),
                            fontSize = 14.sp, color = p.text, fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(8.dp))
                        MeelanoSegmented(
                            items = listOf(
                                stringResource(R.string.regime_auto),
                                stringResource(R.string.regime_calm),
                                stringResource(R.string.regime_tight),
                                stringResource(R.string.regime_blackout),
                            ),
                            index = when (AppSettings.regime) {
                                "auto" -> 0
                                "calm" -> 1
                                "blackout" -> 3
                                else -> 2
                            },
                            onIndex = { i ->
                                AppSettings.setRegime(ctx, listOf("auto", "calm", "tight", "blackout")[i])
                            },
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.set_regime_body),
                            fontSize = 11.sp, color = p.faint, lineHeight = 16.sp,
                        )
                    }
                    PanelDivider()
                    SwitchRow(
                        title = stringResource(R.string.set_frag),
                        body = stringResource(R.string.set_frag_body),
                        on = AppSettings.fragmentAuto,
                        onChange = { AppSettings.setFragmentAuto(ctx, it) },
                    )
                    PanelDivider()
                    SwitchRow(
                        title = stringResource(R.string.set_mux),
                        body = stringResource(R.string.set_mux_body),
                        on = AppSettings.muxEnabled,
                        onChange = { AppSettings.setMuxEnabled(ctx, it) },
                    )
                    PanelDivider()
                    SwitchRow(
                        title = stringResource(R.string.set_reality),
                        body = stringResource(R.string.set_reality_body),
                        on = AppSettings.realityFirst,
                        onChange = { AppSettings.setRealityFirst(ctx, it) },
                    )
                    PanelDivider()
                    SwitchRow(
                        title = stringResource(R.string.set_boot),
                        body = stringResource(R.string.set_boot_body),
                        on = AppSettings.autoConnectOnBoot,
                        onChange = { AppSettings.setAutoConnectOnBoot(ctx, it) },
                    )
                    PanelDivider()
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(
                            stringResource(R.string.set_mtu),
                            fontSize = 14.sp, color = p.text, fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(8.dp))
                        MeelanoSegmented(
                            items = listOf("1280", "1400", "1500"),
                            index = when (AppSettings.mtu) { 1280 -> 0; 1400 -> 1; else -> 2 },
                            onIndex = { i -> AppSettings.setMtu(ctx, listOf(1280, 1400, 1500)[i]) },
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.set_mtu_body),
                            fontSize = 11.sp, color = p.faint, lineHeight = 16.sp,
                        )
                    }
                    PanelDivider()
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(
                            stringResource(R.string.probe_title),
                            fontSize = 14.sp, color = p.text, fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            remember(AppSettings.lastBlockReport) {
                                runCatching {
                                    val o = org.json.JSONObject(AppSettings.lastBlockReport)
                                    val probes = o.optInt("probes")
                                    val alive = (probes - (o.optDouble("tcpFail", 0.0) * probes).toInt()).coerceAtLeast(0)
                                    buildString {
                                        append("$alive/$probes گره از این مسیر پاسخ داد")
                                        val rtt = o.optLong("rtt")
                                        if (rtt > 0) append(" · میانه‌ی تأخیر ${rtt}ms")
                                        append(" · DNS ")
                                        append(if (o.optBoolean("dnsPoisoned")) "دروغ گفته" else "سالم")
                                    }
                                }.getOrDefault(ctx.getString(R.string.probe_none))
                            },
                            fontSize = 11.sp, color = p.muted, lineHeight = 16.sp,
                        )
                    }
                }
            }

            // The update/feedback panel.
            item {
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

                /*
                 * Where the two lists come from, in the user's words. HOST stays the default - the server's
                 * gate, ban ledger and multi-day ranking are things a phone cannot copy - but DIRECT makes the
                 * product work with no host at all, and both paths end in the same cache and the same sheet.
                 */
            }

            // Where the two lists come from, in the user\u2019s words.
            item {
                MeelanoPanel(title = stringResource(R.string.group_feed)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(
                            stringResource(R.string.feed_source),
                            fontSize = 14.sp, color = p.text, fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(8.dp))
                        MeelanoSegmented(
                            items = listOf(
                                stringResource(R.string.feed_source_host),
                                stringResource(R.string.feed_source_auto),
                                stringResource(R.string.feed_source_direct),
                            ),
                            index = AppSettings.feedMode,
                            onIndex = { vm.setFeedSource(it) },
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.feed_source_body),
                            fontSize = 11.sp, color = p.faint, lineHeight = 16.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.feed_now, vm.feedSourceLabel()),
                            fontSize = 11.sp, color = p.muted,
                        )
                        if (feedNotes.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            feedNotes.take(3).forEach { note ->
                                Text(
                                    "· $note",
                                    fontSize = 10.sp, color = p.faint, lineHeight = 15.sp,
                                )
                            }
                        }
                    }
                    PanelDivider()
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(
                            stringResource(R.string.feed_vip_title),
                            fontSize = 14.sp, color = p.text, fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.feed_vip_body),
                            fontSize = 11.sp, color = p.faint, lineHeight = 16.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        val draft = vipDraft ?: savedVip
                        FeedWell(
                            value = draft,
                            onValue = { vipDraft = it; savedLines = -1 },
                            placeholder = if (draft.isBlank()) {
                                stringResource(R.string.feed_vip_placeholder)
                            } else ""
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MeelanoButton(
                                label = stringResource(R.string.feed_vip_save),
                                onClick = {
                                    val text = vipDraft ?: savedVip
                                    vm.saveVipConfigs(text) { n -> savedLines = n; vipDraft = text }
                                },
                                tone = BtnTone.Tonal,
                                size = BtnSize.Small,
                            )
                            Spacer(Modifier.width(8.dp))
                            MeelanoButton(
                                label = stringResource(R.string.feed_vip_clear),
                                onClick = {
                                    vipDraft = ""
                                    savedLines = 0
                                    vm.saveVipConfigs("")
                                },
                                tone = BtnTone.Ghost,
                                size = BtnSize.Small,
                            )
                            Spacer(Modifier.weight(1f))
                            if (savedLines >= 0) {
                                Text(
                                    stringResource(R.string.feed_vip_saved, savedLines),
                                    fontSize = 11.sp, color = p.accent,
                                )
                            } else if (!hasLocalVip) {
                                Text(
                                    stringResource(R.string.feed_vip_none),
                                    fontSize = 11.sp, color = p.faint,
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.feed_sub_url),
                            fontSize = 14.sp, color = p.text, fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.feed_sub_url_body),
                            fontSize = 11.sp, color = p.faint, lineHeight = 16.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        val url = subDraft ?: AppSettings.feedExtraUrl
                        FeedWell(
                            value = url,
                            onValue = { subDraft = it },
                            placeholder = "https://…",
                            singleLine = true,
                            uri = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        MeelanoButton(
                            label = stringResource(
                                if (url.isBlank()) R.string.feed_sub_save else R.string.feed_sub_update,
                            ),
                            onClick = {
                                // never "?: empty": a user who opens the sheet and taps save without
                                // touching the field must not have the URL they already set wiped
                                val v = (subDraft ?: AppSettings.feedExtraUrl).trim()
                                vm.setFeedSubscriptionUrl(v)
                                subDraft = v
                            },
                            tone = BtnTone.Tonal,
                            size = BtnSize.Small,
                        )
                    }
                }
            }

            // Motion. The theme row moved to the home top bar, where a theme control belongs:
            // one tap, its own legend (the icon IS the state), and no settings dive to find it.
            item {
                MeelanoPanel(title = stringResource(R.string.group_general)) {
                    SwitchRow(
                        title = stringResource(R.string.set_reduced),
                        body = stringResource(R.string.set_reduced_body),
                        on = AppSettings.reducedMotion,
                        onChange = { AppSettings.setReducedMotion(ctx, it) },
                    )
                }
            }

            // The reset row lives inside its own panel with a two-step confirm.
            item {
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
            }

            // Permanent access to the shareable report, without having to break the app first.
            item {
                /*
                 * Permanent access to the shareable report. The failure card offers it too, but a person who
                 * already fixed the problem and wants to hand over "what did it look like" shouldn't have to
                 * break the app again to find the button.
                 */
                Spacer(Modifier.height(10.dp))
                MeelanoButton(
                    label = stringResource(R.string.diag_open),
                    onClick = { diagnostics = true },
                    tone = BtnTone.Tonal,
                    size = BtnSize.Small,
                    fill = true,
                )
                if (diagnostics) DiagnosticsSheet(vm = vm, onDismiss = { diagnostics = false })
            }

            // Version + the watchdog state dot.
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 18.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        // build id is part of the answer, not decoration: two APKs with the same
                        // versionCode can be different builds, and "send me what you see" must work
                        stringResource(R.string.set_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE) +
                            if (BuildConfig.BUILD_ID.isNotBlank()) " · ${BuildConfig.BUILD_ID}" else "",
                        fontSize = 11.sp, color = p.faint, modifier = Modifier.weight(1f),
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
}

/**
 * Text entry in the kit's own language: a carved well, an inner top shadow, and the caret in accent.
 * BasicTextField rather than a Material field, because M3 would import its own opinion about padding,
 * focus rings and label floats - all four of which fight the surfaces this app is built from.
 */
@Composable
private fun FeedWell(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = false,
    uri: Boolean = false,
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (singleLine) 40.dp else 92.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(p.shade(0.34f), p.well)), shape)
            .border(1.dp, p.tint(0.10f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = singleLine,
            textStyle = TextStyle(
                fontSize = if (singleLine) 13.sp else 12.sp,
                color = p.text,
                lineHeight = if (singleLine) 18.sp else 17.sp,
            ),
            cursorBrush = SolidColor(p.accent),
            keyboardOptions = KeyboardOptions(keyboardType = if (uri) KeyboardType.Uri else KeyboardType.Text),
            modifier = Modifier.fillMaxWidth(),
        )
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            Text(placeholder, fontSize = 12.sp, color = p.faint)
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
            Text(body, fontSize = 11.sp, color = p.faint, maxLines = 2, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.alpha(if (on) 1f else 0.85f)) { MeelanoSwitch(checked = on, onChange = onChange) }
    }
}

