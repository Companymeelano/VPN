package ir.meelano.vpn.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.meelano.vpn.R
import ir.meelano.vpn.data.AppSettings
import ir.meelano.vpn.data.FeedNode
import ir.meelano.vpn.data.NodeFilters
import ir.meelano.vpn.net.Ladder
import ir.meelano.vpn.net.SmartProfile
import ir.meelano.vpn.ui.theme.LocalPalette
import kotlinx.coroutines.delay

/**
 * «پروفایل هوشمند اتصال» — the section the flow ends in: after the free/VIP lists are downloaded,
 * probed and separated, this sheet answers "for *the service I chose*, which settings connect best?"
 * and applies them in one tap.
 *
 * The verdict is computed by net/SmartProfile (pure, unit-tested) from three real facts — the
 * network regime the app detected, the tier's best measured node's transport, and that tier's
 * median measured ping. Nothing here guesses: if nothing is measured yet the sheet says so and the
 * safe defaults are applied explicitly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartProfileSheet(vm: VpnViewModel, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val syncing by vm.syncing.collectAsState()
    val syncProgress by vm.syncProgress.collectAsState()
    val regime = AppSettings.effectiveRegime()
    val vipNodes by vm.vip.collectAsState()
    val freeNodes by vm.free.collectAsState()
    val proxyNodes by vm.proxies.collectAsState()
    var savedFlash by remember { mutableStateOf(false) }
    LaunchedEffect(savedFlash) {
        if (savedFlash) {
            delay(2_200)
            savedFlash = false
        }
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
                    .padding(bottom = 24.dp),
            ) {
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
                                stringResource(R.string.smart_title),
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
                        Text(
                            stringResource(R.string.smart_body),
                            fontSize = 11.5.sp, color = p.faint, lineHeight = 17.sp,
                            modifier = Modifier.padding(horizontal = 18.dp),
                        )
                    }
                }

                // The staged 3D progress of the download/test/separation flow — real counters,
                // never a simulation (data/SyncProgress is fed by the fetch/probe/scan code).
                if (syncing) {
                    item {
                        Spacer(Modifier.height(12.dp))
                        SyncProgressPanel(
                            progress = syncProgress,
                            modifier = Modifier.padding(horizontal = 18.dp),
                        )
                    }
                }

                item {
                    Spacer(Modifier.height(14.dp))
                    ServiceTuneCard(
                        tier = "vip",
                        tierTitle = stringResource(R.string.smart_vip_title),
                        tierSub = stringResource(R.string.smart_vip_sub),
                        nodes = vipNodes,
                        best = vm.bestFor("vip"),
                        regime = regime,
                        onApply = {
                            applyAdvice(ctx, vipNodes + freeNodes + proxyNodes, "vip")
                            savedFlash = true
                        },
                    )
                }
                item {
                    Spacer(Modifier.height(12.dp))
                    ServiceTuneCard(
                        tier = "free",
                        tierTitle = stringResource(R.string.smart_free_title),
                        tierSub = stringResource(R.string.smart_free_sub),
                        nodes = freeNodes + proxyNodes,
                        best = vm.bestFor("free"),
                        regime = regime,
                        onApply = {
                            applyAdvice(ctx, vipNodes + freeNodes + proxyNodes, "free")
                            savedFlash = true
                        },
                    )
                }

                item {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        stringResource(R.string.smart_note),
                        fontSize = 10.5.sp, color = p.faint, lineHeight = 15.sp,
                        modifier = Modifier.padding(horizontal = 18.dp),
                    )
                }
            }
            MeelanoSaveToast(
                visible = savedFlash,
                message = stringResource(R.string.settings_saved),
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** Apply the tier's advice to the real dials + turn auto-selection on, so the advice sticks. */
private fun applyAdvice(
    ctx: android.content.Context,
    allNodes: List<FeedNode>,
    tier: String,
) {
    val nodes = if (tier == "vip") allNodes.filter { it.isVip } else allNodes.filterNot { it.isVip }
    val usable = nodes.filter { it.isUsable && !it.isRawProxy }
    val best = usable.sortedWith(
        compareByDescending<FeedNode> { it.reliability }
            .thenBy { it.latencyMs ?: Long.MAX_VALUE },
    ).firstOrNull()
    val transport = if (best != null) {
        Ladder.transportOf(best.proto, best.tls, best.network)
    } else "tcp-plain"
    val advice = SmartProfile.advise(
        regime = AppSettings.effectiveRegime(),
        tier = tier,
        transport = transport,
        medianRttMs = NodeFilters.medianPingMs(usable) ?: -1L,
        measured = usable.count { it.latencyMs != null },
    )
    val t = SmartProfile.targets(advice)
    AppSettings.setRegime(ctx, t["regime"] as String)
    AppSettings.setFragmentAuto(ctx, t["fragment_auto"] as Boolean)
    AppSettings.setMuxEnabled(ctx, t["mux_enabled"] as Boolean)
    AppSettings.setRealityFirst(ctx, t["reality_first"] as Boolean)
    AppSettings.setMtu(ctx, t["mtu"] as Int)
    // The advice is about "the service I chose": auto-select must then actually prefer that tier's
    // best row, which is what the ranked auto-picker already does (health first, tier as tiebreak).
    AppSettings.setAutoSelect(ctx, true)
}

@Composable
private fun ServiceTuneCard(
    tier: String,
    tierTitle: String,
    tierSub: String,
    nodes: List<FeedNode>,
    best: FeedNode?,
    regime: ir.meelano.vpn.net.Regime,
    onApply: () -> Unit,
) {
    val p = LocalPalette.current
    val usable = nodes.filter { it.isUsable && !it.isRawProxy }
    val measured = usable.count { it.latencyMs != null }
    val median = NodeFilters.medianPingMs(usable)
    val transport = if (best != null) {
        Ladder.transportOf(best.proto, best.tls, best.network)
    } else "tcp-plain"
    val advice = SmartProfile.advise(
        regime = regime,
        tier = tier,
        transport = transport,
        medianRttMs = median ?: -1L,
        measured = measured,
    )
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(p.surfaceHigh, p.surface)), shape)
            .border(1.dp, p.tint(0.12f), shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tierTitle, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = p.text)
                Spacer(Modifier.height(2.dp))
                Text(tierSub, fontSize = 10.5.sp, color = p.faint, lineHeight = 15.sp)
            }
            GradeBadge(best?.grade ?: "—")
        }
        Spacer(Modifier.height(10.dp))
        if (best != null) {
            Text(
                "بهترین مسیر: ${best.name}  ·  ${best.proto}  ·  " +
                    (best.latencyMs?.let { "${it}ms" } ?: "—"),
                fontSize = 11.5.sp, color = p.muted, lineHeight = 16.sp,
            )
        } else {
            Text(
                stringResource(R.string.smart_no_node),
                fontSize = 11.5.sp, color = p.warn, lineHeight = 16.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "$measured/${usable.size} مسیر اندازه‌گیری شد" +
                (median?.let { "  ·  میانه‌ی پینگ ${it}ms" } ?: ""),
            fontSize = 10.5.sp,
            color = p.faint,
            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
        )
        Spacer(Modifier.height(10.dp))
        // The dials this advice will write — visible before the tap, not after.
        AdviceChips(advice)
        Spacer(Modifier.height(10.dp))
        Column {
            advice.reasons.forEach { reason ->
                Row(verticalAlignment = Alignment.Top) {
                    Text("·", fontSize = 11.sp, color = p.accent)
                    Spacer(Modifier.width(6.dp))
                    Text(reason, fontSize = 10.5.sp, color = p.muted, lineHeight = 15.sp)
                }
                Spacer(Modifier.height(3.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        MeelanoButton(
            label = stringResource(R.string.smart_apply),
            onClick = onApply,
            tone = BtnTone.Primary,
            size = BtnSize.Medium,
            fill = true,
        )
    }
}

@Composable
private fun AdviceChips(advice: SmartProfile.Advice) {
    val p = LocalPalette.current
    val items = listOf(
        "تکه‌تکه‌سازی" to if (advice.fragment) "${advice.fragSize}B" else "خاموش",
        "Mux" to if (advice.mux) "${advice.muxConcurrency}×" else "خاموش",
        "MTU" to advice.mtu.toString(),
        "Keepalive" to "${advice.keepAliveSec}s",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 30.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { (k, v) ->
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(p.tint(0.07f))
                    .border(1.dp, p.tint(0.10f), RoundedCornerShape(10.dp))
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(k, fontSize = 8.5.sp, color = p.faint, maxLines = 1)
                Text(
                    v,
                    fontSize = 11.sp,
                    color = p.accent,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                )
            }
        }
    }
}

@Composable
private fun GradeBadge(grade: String) {
    val p = LocalPalette.current
    val ink = when (grade) {
        "A" -> p.gradeA
        "B" -> p.info
        "C" -> p.warn
        else -> p.faint
    }
    Box(
        Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.verticalGradient(listOf(ink.copy(alpha = 0.24f), ink.copy(alpha = 0.08f))))
            .border(1.dp, ink.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(grade, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ink)
    }
}
