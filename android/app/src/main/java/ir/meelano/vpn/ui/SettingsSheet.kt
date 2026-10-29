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
import ir.meelano.vpn.data.FeedScheduler
import ir.meelano.vpn.data.PerAppRouting
import ir.meelano.vpn.keepalive.KeepAlive
import ir.meelano.vpn.vpn.MeelanoVpnService
import kotlinx.coroutines.delay

/**
 * One draft, one save button — the settings screen the user asked for.
 *
 * Every dial in the [SettingsDraft] below is edited in *local* state and written to [AppSettings]
 * only by «ذخیره‌ی تنظیمات», which then confirms with «تنظیمات ذخیره شد». Two rows are deliberately
 * outside the draft and keep their instant behaviour, because their effect is a *process* and not a
 * preference: per-app routing rebuilds the live tunnel, and the feed-vault fields have their own
 * save buttons that count parsed lines.
 *
 * Grouping rule (the second thing the user asked for): each dial lives under exactly one
 * subcategory — دریافت و فید / منابع و سرویس / اتصال / ضدفیلتر / مسیریابی / ظاهر / پیشرفته — and a
 * panel is a promise that rows inside it belong together. The gaps between panels are the only
 * separators the eye needs.
 */
private data class SettingsDraft(
    val smartReconnect: Boolean = true,
    val killSwitch: Boolean = true,
    val secureDns: Boolean = true,
    val autoConnectOnBoot: Boolean = true,
    val regime: String = "tight",
    val fragmentAuto: Boolean = true,
    val muxEnabled: Boolean = true,
    val realityFirst: Boolean = true,
    val mtu: Int = 1280,
    val autoFetch: Boolean = true,
    val feedIntervalHours: Int = 6,
    val autoUpdate: Boolean = true,
    val autoCheckUpdate: Boolean = true,
    val feedback: Boolean = true,
    val reducedMotion: Boolean = false,
)

private fun draftFrom(): SettingsDraft = SettingsDraft(
    smartReconnect = AppSettings.smartReconnect,
    killSwitch = AppSettings.killSwitch,
    secureDns = AppSettings.secureDns,
    autoConnectOnBoot = AppSettings.autoConnectOnBoot,
    regime = AppSettings.regime,
    fragmentAuto = AppSettings.fragmentAuto,
    muxEnabled = AppSettings.muxEnabled,
    realityFirst = AppSettings.realityFirst,
    mtu = AppSettings.mtu,
    autoFetch = AppSettings.autoFetch,
    feedIntervalHours = AppSettings.feedIntervalHours,
    autoUpdate = AppSettings.autoUpdate,
    autoCheckUpdate = AppSettings.autoCheckUpdate,
    feedback = AppSettings.feedback,
    reducedMotion = AppSettings.reducedMotion,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(vm: VpnViewModel, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmReset by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(false) }
    var routing by remember { mutableStateOf(false) }
    var smart by remember { mutableStateOf(false) }
    var updateSheet by remember { mutableStateOf(false) }
    // The on-device list has no host to ask "what happened", so the sheet carries the answer: which
    // sources answered, and what was thrown away. Local state only - nothing here survives the sheet.
    val feedNotes by vm.feedNotes.collectAsState()
    val syncing by vm.syncing.collectAsState()
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

    // ── the draft: edited live, written only by the save button ──────────────────────────────
    var draft by remember { mutableStateOf(draftFrom()) }
    val initial = remember { draftFrom() }
    val dirty = draft != initial
    var savedFlash by remember { mutableStateOf(false) }
    LaunchedEffect(savedFlash) {
        if (savedFlash) {
            delay(2_200)
            savedFlash = false
        }
    }
    fun commit() {
        AppSettings.setSmartReconnect(ctx, draft.smartReconnect)
        AppSettings.setKillSwitch(ctx, draft.killSwitch)
        AppSettings.setSecureDns(ctx, draft.secureDns)
        AppSettings.setAutoConnectOnBoot(ctx, draft.autoConnectOnBoot)
        AppSettings.setRegime(ctx, draft.regime)
        AppSettings.setFragmentAuto(ctx, draft.fragmentAuto)
        AppSettings.setMuxEnabled(ctx, draft.muxEnabled)
        AppSettings.setRealityFirst(ctx, draft.realityFirst)
        AppSettings.setMtu(ctx, draft.mtu)
        AppSettings.setAutoFetch(ctx, draft.autoFetch)
        AppSettings.setFeedIntervalHours(ctx, draft.feedIntervalHours)
        AppSettings.setAutoUpdate(ctx, draft.autoUpdate)
        AppSettings.setAutoCheckUpdate(ctx, draft.autoCheckUpdate)
        AppSettings.setFeedback(ctx, draft.feedback)
        AppSettings.setReducedMotion(ctx, draft.reducedMotion)
        // Side effects the dials promise: the watchdog exists only while smart-reconnect is on, and
        // the periodic feed sync must re-arm with the new interval immediately, not on next launch.
        runCatching { if (draft.smartReconnect) KeepAlive.enableWatchdog(ctx) else KeepAlive.disableWatchdog(ctx) }
        runCatching { FeedScheduler.schedule(ctx) }
        runCatching { ir.meelano.vpn.update.UpdateScheduler.schedule(ctx) }
        savedFlash = true
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = p.surface,
        contentColor = p.text,
        dragHandle = null,
    ) {
        Box(Modifier.fillMaxWidth()) {
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
                                .align(Alignment.CenterHorizontally),
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

                // ── «پروفایل هوشمند» — the section that tunes the app for the service chosen ──
                item {
                    MeelanoPanel(title = stringResource(R.string.smart_title)) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                            Text(
                                stringResource(R.string.smart_body),
                                fontSize = 11.sp, color = p.faint, lineHeight = 16.sp,
                            )
                            Spacer(Modifier.height(10.dp))
                            MeelanoButton(
                                label = stringResource(R.string.smart_open),
                                onClick = { smart = true },
                                tone = BtnTone.Primary,
                                size = BtnSize.Medium,
                                fill = true,
                                iconRes = R.drawable.ic_bolt,
                            )
                        }
                    }
                }

                // ── «دریافت و فید» — automatic fetching, its schedule, and the data dials ──
                item {
                    MeelanoPanel(title = stringResource(R.string.group_fetch)) {
                        SwitchRow(
                            title = stringResource(R.string.set_autofetch),
                            body = stringResource(R.string.set_autofetch_body),
                            on = draft.autoFetch,
                            onChange = { draft = draft.copy(autoFetch = it) },
                        )
                        PanelDivider()
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                            Text(
                                stringResource(R.string.set_interval),
                                fontSize = 14.sp, color = p.text, fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(8.dp))
                            MeelanoSegmented(
                                items = listOf("۱ ساعت", "۳ ساعت", "۶ ساعت", "۱۲ ساعت", "۲۴ ساعت"),
                                index = FeedScheduler.INTERVALS.indexOf(draft.feedIntervalHours)
                                    .coerceAtLeast(0),
                                onIndex = { i -> draft = draft.copy(feedIntervalHours = FeedScheduler.INTERVALS[i]) },
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.set_interval_body),
                                fontSize = 11.sp, color = p.faint, lineHeight = 16.sp,
                            )
                        }
                        PanelDivider()
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(
                                stringResource(R.string.last_update, relTime(vm.generatedAt("free"))),
                                fontSize = 11.sp, color = p.muted,
                            )
                            Spacer(Modifier.height(8.dp))
                            MeelanoButton(
                                label = stringResource(R.string.sync_now),
                                onClick = { vm.refreshBoth() },
                                tone = BtnTone.Tonal,
                                size = BtnSize.Small,
                                iconRes = R.drawable.ic_refresh,
                                enabled = !syncing,
                                loading = syncing,
                            )
                        }
                        PanelDivider()
                        SwitchRow(
                            title = stringResource(R.string.set_feedback),
                            body = stringResource(R.string.set_feedback_body),
                            on = draft.feedback,
                            onChange = { draft = draft.copy(feedback = it) },
                        )
                    }
                }

                // ── «به‌روزرسانی برنامه» — the new automatic version-check section ──
                item {
                    MeelanoPanel(title = stringResource(R.string.group_update)) {
                        SwitchRow(
                            title = stringResource(R.string.set_autocheck),
                            body = stringResource(R.string.set_autocheck_body),
                            on = draft.autoCheckUpdate,
                            onChange = { draft = draft.copy(autoCheckUpdate = it) },
                        )
                        PanelDivider()
                        SwitchRow(
                            title = stringResource(R.string.set_update),
                            body = stringResource(R.string.set_update_body),
                            on = draft.autoUpdate,
                            onChange = { draft = draft.copy(autoUpdate = it) },
                        )
                        PanelDivider()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                            Text(
                                stringResource(R.string.upd_installed, BuildConfig.VERSION_NAME),
                                fontSize = 11.sp, color = p.faint,
                                style = TextStyle(fontFeatureSettings = "tnum"),
                            )
                            Spacer(Modifier.height(4.dp))
                            val updState by vm.updateState.collectAsState()
                            val statusText = when (val s = updState) {
                                is ir.meelano.vpn.update.UpdateManager.State.Checking ->
                                    stringResource(R.string.upd_status_checking)
                                is ir.meelano.vpn.update.UpdateManager.State.Available ->
                                    stringResource(R.string.upd_status_available, s.info.versionName)
                                is ir.meelano.vpn.update.UpdateManager.State.Failed ->
                                    stringResource(R.string.upd_status_failed)
                                is ir.meelano.vpn.update.UpdateManager.State.ReadyToInstall ->
                                    stringResource(R.string.upd_saved)
                                else -> stringResource(R.string.upd_status_uptodate)
                            }
                            Text(statusText, fontSize = 12.sp, color = p.muted)
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MeelanoButton(
                                    label = stringResource(R.string.upd_check_now),
                                    onClick = { vm.updates.checkAsync() },
                                    tone = BtnTone.Tonal,
                                    size = BtnSize.Small,
                                    iconRes = R.drawable.ic_refresh,
                                    loading = updState is ir.meelano.vpn.update.UpdateManager.State.Checking,
                                )
                                if (updState is ir.meelano.vpn.update.UpdateManager.State.Available ||
                                    updState is ir.meelano.vpn.update.UpdateManager.State.Downloading
                                ) {
                                    Spacer(Modifier.width(8.dp))
                                    MeelanoButton(
                                        label = stringResource(R.string.upd_open),
                                        onClick = { updateSheet = true },
                                        tone = BtnTone.Primary,
                                        size = BtnSize.Small,
                                        iconRes = R.drawable.ic_download,
                                    )
                                }
                            }
                        }
                    }
                }

                // ── «منابع و سرویس» — where the lists come from, and the user's own VIP ──
                item {
                    MeelanoPanel(title = stringResource(R.string.group_feed)) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                            Text(
                                stringResource(R.string.feed_source_direct_title),
                                fontSize = 14.sp, color = p.text, fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.feed_source_direct_body),
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
                            val d = vipDraft ?: savedVip
                            FeedWell(
                                value = d,
                                onValue = { vipDraft = it; savedLines = -1 },
                                placeholder = if (d.isBlank()) {
                                    stringResource(R.string.feed_vip_placeholder)
                                } else "",
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

                // ── «اتصال» — the tunnel's behaviour switches ──
                item {
                    MeelanoPanel(title = stringResource(R.string.group_vpn)) {
                        SwitchRow(
                            title = stringResource(R.string.set_keepalive),
                            body = stringResource(R.string.set_keepalive_body),
                            on = draft.smartReconnect,
                            onChange = { draft = draft.copy(smartReconnect = it) },
                        )
                        PanelDivider()
                        SwitchRow(
                            title = stringResource(R.string.set_killswitch),
                            body = stringResource(R.string.set_killswitch_body),
                            on = draft.killSwitch,
                            onChange = { draft = draft.copy(killSwitch = it) },
                        )
                        PanelDivider()
                        SwitchRow(
                            title = stringResource(R.string.set_dns),
                            body = stringResource(R.string.set_dns_body),
                            on = draft.secureDns,
                            onChange = { draft = draft.copy(secureDns = it) },
                        )
                        PanelDivider()
                        SwitchRow(
                            title = stringResource(R.string.set_boot),
                            body = stringResource(R.string.set_boot_body),
                            on = draft.autoConnectOnBoot,
                            onChange = { draft = draft.copy(autoConnectOnBoot = it) },
                        )
                    }
                }

                // ── «ضدفیلتر» — the four transport dials + MTU ──
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
                                index = when (draft.regime) {
                                    "auto" -> 0
                                    "calm" -> 1
                                    "blackout" -> 3
                                    else -> 2
                                },
                                onIndex = { i ->
                                    draft = draft.copy(regime = listOf("auto", "calm", "tight", "blackout")[i])
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
                            on = draft.fragmentAuto,
                            onChange = { draft = draft.copy(fragmentAuto = it) },
                        )
                        PanelDivider()
                        SwitchRow(
                            title = stringResource(R.string.set_mux),
                            body = stringResource(R.string.set_mux_body),
                            on = draft.muxEnabled,
                            onChange = { draft = draft.copy(muxEnabled = it) },
                        )
                        PanelDivider()
                        SwitchRow(
                            title = stringResource(R.string.set_reality),
                            body = stringResource(R.string.set_reality_body),
                            on = draft.realityFirst,
                            onChange = { draft = draft.copy(realityFirst = it) },
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
                                index = when (draft.mtu) { 1280 -> 0; 1400 -> 1; else -> 2 },
                                onIndex = { i -> draft = draft.copy(mtu = listOf(1280, 1400, 1500)[i]) },
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.set_mtu_body),
                                fontSize = 11.sp, color = p.faint, lineHeight = 16.sp,
                            )
                        }
                    }
                }

                // ── «مسیریابی» — per-app rules; instant-apply because they rebuild the tunnel ──
                item {
                    MeelanoPanel(title = stringResource(R.string.group_routing)) {
                        SwitchRow(
                            title = stringResource(R.string.set_routing_enabled),
                            body = stringResource(R.string.set_routing_enabled_body),
                            on = PerAppRouting.enabled,
                            onChange = {
                                PerAppRouting.setEnabled(ctx, it)
                                if (MeelanoVpnService.isRunning()) MeelanoVpnService.reapplyRouting(ctx)
                            },
                        )
                        PanelDivider()
                        SwitchRow(
                            title = stringResource(R.string.set_routing_auto),
                            body = stringResource(R.string.set_routing_auto_body),
                            on = PerAppRouting.autoBypass,
                            onChange = {
                                PerAppRouting.setAutoBypass(ctx, it)
                                if (MeelanoVpnService.isRunning()) MeelanoVpnService.reapplyRouting(ctx)
                            },
                        )
                        PanelDivider()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { routing = true }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.routing_manage),
                                    fontSize = 14.sp, color = p.text, fontWeight = FontWeight.Medium,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    stringResource(R.string.routing_manage_body),
                                    fontSize = 11.sp, color = p.faint, lineHeight = 16.sp,
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            MeelanoIconButton(
                                iconRes = R.drawable.ic_apps,
                                contentDescription = stringResource(R.string.routing_manage),
                                onClick = { routing = true },
                                sizeDp = 38.dp,
                                corner = 12.dp,
                            )
                        }
                    }
                }

                // ── «ظاهر» — motion; the theme toggle lives on the home top bar ──
                item {
                    MeelanoPanel(title = stringResource(R.string.group_appearance)) {
                        SwitchRow(
                            title = stringResource(R.string.set_reduced),
                            body = stringResource(R.string.set_reduced_body),
                            on = draft.reducedMotion,
                            onChange = { draft = draft.copy(reducedMotion = it) },
                        )
                    }
                }

                // ── «پیشرفته» — the local probe readout, diagnostics, and the reset ──
                item {
                    MeelanoPanel(title = stringResource(R.string.group_advanced)) {
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
                        PanelDivider()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                            MeelanoButton(
                                label = stringResource(R.string.diag_open),
                                onClick = { diagnostics = true },
                                tone = BtnTone.Tonal,
                                size = BtnSize.Small,
                                fill = true,
                            )
                        }
                        PanelDivider()
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

                // ── the save bar: the one place settings leave the draft ──
                item {
                    Spacer(Modifier.height(14.dp))
                    Column(Modifier.padding(horizontal = 18.dp)) {
                        if (dirty) {
                            Text(
                                stringResource(R.string.unsaved_hint),
                                fontSize = 11.sp, color = p.warn,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                        MeelanoButton(
                            label = stringResource(R.string.save_settings),
                            onClick = { commit() },
                            tone = BtnTone.Primary,
                            size = BtnSize.Large,
                            fill = true,
                            enabled = dirty,
                        )
                    }
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
            MeelanoSaveToast(
                visible = savedFlash,
                message = stringResource(R.string.settings_saved),
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        if (diagnostics) DiagnosticsSheet(vm = vm, onDismiss = { diagnostics = false })
        if (routing) AppRoutingSheet(onDismiss = { routing = false })
        if (smart) SmartProfileSheet(vm = vm, onDismiss = { smart = false })
        if (updateSheet) UpdateSheet(vm = vm, onDismiss = { updateSheet = false })
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
