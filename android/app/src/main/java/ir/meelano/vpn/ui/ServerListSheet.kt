package ir.meelano.vpn.ui

import ir.meelano.vpn.ui.theme.LocalMotionPrefs
import ir.meelano.vpn.ui.theme.LocalPalette

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.meelano.vpn.R
import ir.meelano.vpn.data.AppSettings
import ir.meelano.vpn.data.FeedNode
import ir.meelano.vpn.data.Prefs
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border

/**
 * The list. VIP and free share one row anatomy on purpose: one shape means the fetch, the cache and
 * the UI are each written once — which is exactly why the free tab "didn't apply" before: it was a
 * different shape, so it carried a different bug.
 *
 * Two things a designer insists on and an engineer proves:
 *  - the segment control is two visible choices, never a dropdown: "which list am I on" is state;
 *  - a row is never only a number: grade letter + quality bar + latency, so the list still reads for a
 *    colour-blind user and on a 10-year-old TN panel in daylight.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListSheet(
    vm: VpnViewModel,
    vip: List<FeedNode>,
    free: List<FeedNode>,
    activeId: String?,
    syncing: Boolean,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var seg by remember { mutableStateOf(if (vip.isEmpty() && free.isNotEmpty()) SEG_FREE else SEG_VIP) }
    var filter by remember { mutableStateOf(FILTER_ALL) }
    var pinned by remember { mutableStateOf(Prefs.pinnedIds(ctx)) }
    // Which builder made this list, and what it had to throw away. Only interesting when the list looks
    // thin - which is precisely when a silent difference turns into a bug report.
    val feedNotes by vm.feedNotes.collectAsState()

    val source = if (seg == SEG_FREE) free else vip
    val rows = remember(source, filter, pinned) {
        val base = when (filter) {
            FILTER_NEAR -> source.filter { it.latencyMs != null }
            FILTER_FAST -> source.filter { (it.latencyMs ?: 99_999L) <= 400 }
            FILTER_WORKS -> source.filter { it.grade == "A" || it.grade == "B" }
            else -> source
        }
        base.sortedWith(
            compareByDescending<FeedNode> { it.id in pinned }
                .thenBy { gradeRank(it.grade) }
                .thenBy { it.latencyMs ?: 99_999L }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = p.surface,
        contentColor = p.text,
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth()) {
            // a 32x3 grab bar, not the default M3 pill: it reads as "pull" and it is 3dp, not 4
            Box(
                Modifier
                    .padding(top = 10.dp)
                    .size(width = 32.dp, height = 3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(p.tint(0.18f))
                    .align(Alignment.CenterHorizontally)
            )

            Row(
                Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.sheet_servers),
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

            Spacer(Modifier.height(12.dp))

            Segmented(
                items = listOf(
                    label(R.string.seg_vip, vip.size),
                    label(R.string.seg_free, free.size),
                ),
                index = seg,
                onIndex = { seg = it },
            )

            Spacer(Modifier.height(10.dp))

            Row(
                // four chips do not fit a 360dp screen once they are real buttons; the row scrolls and
                // the edge fade says so — hiding a filter behind an ellipsis is worse than a thumb-drag
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MeelanoChip(stringResource(R.string.filter_all), filter == FILTER_ALL, { filter = FILTER_ALL })
                MeelanoChip(
                    stringResource(R.string.filter_near), filter == FILTER_NEAR, { filter = FILTER_NEAR },
                    leadingIcon = R.drawable.ic_location,
                )
                MeelanoChip(
                    stringResource(R.string.filter_fast), filter == FILTER_FAST, { filter = FILTER_FAST },
                    leadingIcon = R.drawable.ic_speed,
                )
                MeelanoChip(stringResource(R.string.filter_works), filter == FILTER_WORKS, { filter = FILTER_WORKS })
            }

            Spacer(Modifier.height(6.dp))

            // "auto" is a row, not a checkbox buried in settings: it is the choice most users want.
            // The switch is the state (never a "✓" character, which is a font-failure waiting to happen),
            // and the whole row is the target, with the same press light the settings rows use.
            AutoRow(
                on = AppSettings.autoSelect,
                onChange = { AppSettings.setAutoSelect(ctx, it) },
                title = stringResource(R.string.seg_auto),
                body = stringResource(R.string.auto_desc),
            )

            Spacer(Modifier.height(2.dp))

            when {
                syncing && source.isEmpty() -> Column(Modifier.padding(vertical = 6.dp)) {
                    repeat(4) { SkeletonRow() }
                }
                rows.isEmpty() -> {
                    val err by vm.feedError.collectAsState()
                    EmptyState(vip = seg == SEG_VIP, error = err, onRetry = { vm.refreshBoth() })
                }
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    items(rows, key = { it.id }) { n ->
                        ServerRow(
                            node = n,
                            selected = n.id == activeId,
                            onClick = {
                                AppSettings.setAutoSelect(ctx, false)
                                vm.select(n)
                                onDismiss()
                            },
                            onTogglePin = {
                                vm.pin(n)
                                pinned = Prefs.pinnedIds(ctx)
                            },
                        )
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.last_update, relTime(vm.generatedAt(if (seg == SEG_FREE) "free" else "vip"))),
                        fontSize = 11.sp, color = p.faint,
                    )
                    Text(vm.feedSourceLabel(), fontSize = 10.sp, color = p.faint.copy(alpha = 0.78f))
                    feedNotes.firstOrNull()?.let { note ->
                        Text(
                            "· $note",
                            fontSize = 10.sp, color = p.faint.copy(alpha = 0.85f),
                            maxLines = 2, lineHeight = 14.sp,
                        )
                    }
                }
                MeelanoButton(
                    label = stringResource(R.string.row_retest),
                    onClick = { vm.refreshBoth() },
                    tone = BtnTone.Tonal,
                    size = BtnSize.Small,
                    iconRes = R.drawable.ic_refresh,
                    enabled = !syncing,
                    loading = syncing,
                )
            }
        }
    }
}

/** "وی‌آی‌پی ۴" — count in a superscript-ish tabular style so the label width never jitters */
@Composable
private fun label(res: Int, count: Int): String =
    stringResource(res) + if (count > 0) "  ·  $count" else ""

/*
 * The two-choice control (وی‌آی‌پی / رایگان) is the kit's `MeelanoSegmented`: a carved well with one
 * raised pill that slides on a spring. Kept under a local name so nothing else in the file changed.
 */
@Composable
internal fun Segmented(items: List<String>, index: Int, onIndex: (Int) -> Unit) {
    MeelanoSegmented(
        items = items,
        index = index,
        onIndex = onIndex,
        modifier = Modifier.padding(horizontal = 18.dp),
    )
}

/**
 * Kept as names because three files call them; both now delegate to the control kit, so the chips in
 * this sheet and the pill on the settings sheet are literally the same code, not the same styling.
 */
@Composable
internal fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    MeelanoChip(label = label, on = on, onClick = onClick)
}

/** loading is a shape, not a spinner: the eye already knows where the rows will land */
@Composable
private fun SkeletonRow() {
    val p = LocalPalette.current
    // up to four of these can be on screen at once; with motion off none of them animates
    val t = if (LocalMotionPrefs.current.loopsAllowed()) rememberInfiniteTransition(label = "sk") else null
    val a = t?.animateFloat(
        0.16f, 0.32f,
        infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "a",
    )
    val alpha = a?.value ?: 0.24f
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 19.dp, height = 14.dp).clip(RoundedCornerShape(3.dp)).background(p.tint(alpha)))
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth(0.45f).height(9.dp).clip(RoundedCornerShape(4.dp)).background(p.tint(alpha)))
            Box(Modifier.fillMaxWidth(0.25f).height(7.dp).clip(RoundedCornerShape(4.dp)).background(p.tint(alpha * 0.7f)))
        }
        Spacer(Modifier.size(12.dp))
        Box(Modifier.size(width = 56.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(p.tint(alpha)))
    }
}

@Composable
private fun EmptyState(vip: Boolean, error: String?, onRetry: () -> Unit) {
    MeelanoEmptyState(
        title = stringResource(if (vip) R.string.empty_vip_title else R.string.empty_free_title),
        body = stringResource(if (vip) R.string.empty_vip_body else R.string.empty_free_body),
        action = stringResource(R.string.retry),
        onAction = onRetry,
        iconRes = if (vip) R.drawable.ic_shield else R.drawable.ic_bolt,
        error = error,
    )
}

@Composable
private fun AutoRow(on: Boolean, onChange: (Boolean) -> Unit, title: String, body: String) {
    val p = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val shrink by animateFloatAsState(if (pressed) 0.994f else 1f, label = "autoRow")
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .scale(shrink)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    if (on) {
                        listOf(p.accent.copy(alpha = if (pressed) 0.18f else 0.13f), p.accent.copy(alpha = 0.05f))
                    } else {
                        listOf(
                            if (pressed) p.tint(0.08f) else Color.Transparent,
                            if (pressed) p.shade(0.12f) else Color.Transparent,
                        )
                    },
                ),
                shape,
            )
            .border(1.dp, if (on) p.accent.copy(alpha = 0.34f) else Color.Transparent, shape)
            .clickable(interactionSource = src, indication = null) { onChange(!on) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 14.sp,
                color = if (on) p.text else p.muted,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(body, fontSize = 11.sp, color = p.faint, maxLines = 2, lineHeight = 16.sp)
        }
        Spacer(Modifier.size(12.dp))
        MeelanoSwitch(checked = on, onChange = onChange)
    }
}

/** grade order: A first. Shared with the row sort in this sheet and the auto-pick in VpnViewModel. */
internal fun gradeRank(g: String) = when (g) { "A" -> 0; "B" -> 1; "C" -> 2; else -> 3 }

/** Persian relative time; "لحظاتی پیش" beats a raw timestamp because nobody reads a clock under stress */
internal fun relTime(ts: Long): String {
    if (ts <= 0) return "—"
    val d = System.currentTimeMillis() - ts
    val m = d / 60_000
    return when {
        m < 1 -> "لحظاتی پیش"
        m < 60 -> "$m دقیقه پیش"
        m < 60 * 24 -> "${m / 60} ساعت پیش"
        else -> "${m / (60 * 24)} روز پیش"
    }
}

private const val SEG_VIP = 0
private const val SEG_FREE = 1
private const val FILTER_ALL = 0
private const val FILTER_NEAR = 1
private const val FILTER_FAST = 2
private const val FILTER_WORKS = 3
