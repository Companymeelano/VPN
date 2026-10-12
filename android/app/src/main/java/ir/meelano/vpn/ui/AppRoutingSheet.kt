package ir.meelano.vpn.ui

import ir.meelano.vpn.ui.theme.LocalPalette

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.meelano.vpn.R
import ir.meelano.vpn.data.PerAppRouting
import ir.meelano.vpn.data.RouteClass
import ir.meelano.vpn.data.RoutingPolicy
import ir.meelano.vpn.vpn.MeelanoVpnService

/**
 * The editable half of per-app routing (the automatic half is data/PerAppRouting.kt): everything the
 * detector found, everything the user forced, a search box and a manual-add field - because "my bank
 * app" must be fixable in twenty seconds by someone who has never seen a package name in their life,
 * and because a detector list that cannot be overruled is a list that gets the VPN deleted the first
 * time it guesses wrong.
 *
 * One row = one app, and the switch reads the way the user talks: ON = "این برنامه خارج از تونل".
 * The trash icon is not delete-app; it is "forget my override, let auto-detection decide again", and
 * for manual entries it removes the entry - the hint row says which one is in effect.
 *
 * Changes apply at establish() time. If a tunnel is up when the sheet closes, the service rebuilds it
 * on the same node (MeelanoVpnService.reapplyRouting); otherwise the next connect picks the rules up.
 * Either way the user never gets a silent "nothing happened".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoutingSheet(onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // revision is the observable: every rules write bumps it, so the row list recomposes without
    // threading collections through Compose state. scanTick covers the rescan button, whose result
    // changes the cache but not the rules.
    val revision = PerAppRouting.revision
    var scanTick by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var addDraft by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    val connected = MeelanoVpnService.isRunning()

    val rows = remember(revision, scanTick, query) {
        val scanned = PerAppRouting.scan(ctx)
        val installed = scanned.mapTo(mutableSetOf()) { it.pkg }
        val bypass = PerAppRouting.bypassSet
        val tunnel = PerAppRouting.tunnelSet
        val auto = PerAppRouting.enabled && PerAppRouting.autoBypass

        val scannedRows = scanned.map { s ->
            val forced: Boolean? = when {
                bypass.contains(s.pkg) -> true
                tunnel.contains(s.pkg) -> false
                else -> null
            }
            RoutingRowModel(
                pkg = s.pkg,
                label = s.label,
                cls = s.cls,
                custom = false,
                bypassed = forced ?: (auto && s.cls != RouteClass.FOREIGN),
            )
        }
        // Manual entries for packages that are not (currently) installed on this phone.
        val customRows = bypass.filterNot { installed.contains(it) }.map { pkg ->
            RoutingRowModel(pkg, pkg, RoutingPolicy.classify(pkg), custom = true, bypassed = true)
        }
        val q = query.trim().lowercase()
        (scannedRows + customRows)
            .filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
            .sortedWith(compareBy({ if (it.cls == RouteClass.BANKING) 0 else 1 }, { it.label.lowercase() }))
    }
    val bypassCount = remember(revision, scanTick) {
        val scanned = PerAppRouting.scan(ctx)
        RoutingRowDefaults.bypassCount(scanned.mapTo(mutableSetOf()) { it.pkg })
    }

    fun close() {
        // The interface rules are baked at establish(): if a tunnel is live, rebuild it on the same
        // node now - leaving it stale is how "I switched it and nothing changed" is born.
        if (dirty && MeelanoVpnService.isRunning()) MeelanoVpnService.reapplyRouting(ctx)
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = { close() },
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
                            stringResource(R.string.routing_sheet_title),
                            fontSize = 17.sp, fontWeight = FontWeight.Bold, color = p.text,
                        )
                        Spacer(Modifier.weight(1f))
                        MeelanoIconButton(
                            iconRes = R.drawable.ic_refresh,
                            contentDescription = stringResource(R.string.routing_rescan),
                            onClick = {
                                PerAppRouting.scan(ctx, refresh = true)
                                scanTick++
                            },
                            sizeDp = 38.dp,
                            corner = 12.dp,
                        )
                        Spacer(Modifier.width(6.dp))
                        MeelanoIconButton(
                            iconRes = R.drawable.ic_close,
                            contentDescription = stringResource(R.string.close),
                            onClick = { close() },
                            sizeDp = 38.dp,
                            corner = 12.dp,
                        )
                    }
                }
            }

            // The banking promise, in one sentence: why those rows are pinned at the top and what
            // "always direct" means when the tunnel itself is down.
            item {
                MeelanoPanel {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(
                            stringResource(R.string.routing_bank_note),
                            fontSize = 11.sp, color = p.muted, lineHeight = 17.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.routing_count, bypassCount),
                            fontSize = 11.sp, color = p.accent,
                        )
                    }
                }
            }

            // Search + manual add, one panel: the two text entries of this screen live together so
            // "find an app" and "type an app" never feel like separate features.
            item {
                MeelanoPanel {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        RoutingField(
                            value = query,
                            onValue = { query = it },
                            placeholder = stringResource(R.string.routing_search),
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                RoutingField(
                                    value = addDraft,
                                    onValue = { addDraft = it; addError = false },
                                    placeholder = stringResource(R.string.routing_add_hint),
                                    ltr = true,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            MeelanoButton(
                                label = stringResource(R.string.routing_add),
                                onClick = {
                                    val pkg = addDraft.trim()
                                    if (RoutingPolicy.isValidPackage(pkg)) {
                                        PerAppRouting.forceBypass(ctx, pkg)
                                        addDraft = ""
                                        addError = false
                                        dirty = true
                                    } else {
                                        addError = true
                                    }
                                },
                                iconRes = R.drawable.ic_add,
                                tone = BtnTone.Tonal,
                                size = BtnSize.Small,
                            )
                        }
                        if (addError) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.routing_add_bad),
                                fontSize = 10.5.sp, color = p.faint,
                            )
                        }
                    }
                }
            }

            if (rows.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.routing_empty),
                        fontSize = 12.sp, color = p.faint,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                    )
                }
            } else {
                items(rows, key = { it.pkg }) { row ->
                    val forced = PerAppRouting.bypassSet.contains(row.pkg) ||
                        PerAppRouting.tunnelSet.contains(row.pkg)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 62.dp)
                            .padding(horizontal = 16.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (row.label.isBlank()) row.pkg else row.label,
                                fontSize = 13.5.sp, color = p.text, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                row.pkg,
                                fontSize = 10.sp, color = p.faint, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(textDirection = TextDirection.Ltr),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        RoutingChip(
                            text = stringResource(
                                when {
                                    row.custom -> R.string.routing_chip_custom
                                    row.cls == RouteClass.BANKING -> R.string.routing_chip_bank
                                    row.cls == RouteClass.IRANIAN -> R.string.routing_chip_iranian
                                    else -> R.string.routing_chip_foreign
                                },
                            ),
                            highlight = row.cls == RouteClass.BANKING && !row.custom,
                        )
                        Spacer(Modifier.width(8.dp))
                        MeelanoSwitch(
                            checked = row.bypassed,
                            onChange = { wantsBypass ->
                                if (wantsBypass) {
                                    // Coming back from a forced-tunnel override is "undo", not a new force.
                                    if (PerAppRouting.tunnelSet.contains(row.pkg)) {
                                        PerAppRouting.clearOverride(ctx, row.pkg)
                                    } else {
                                        PerAppRouting.forceBypass(ctx, row.pkg)
                                    }
                                } else {
                                    // OFF must mean "through the tunnel" even for an auto-detected app,
                                    // so it is always an explicit force - never a silent no-op.
                                    PerAppRouting.forceTunnel(ctx, row.pkg)
                                }
                                dirty = true
                            },
                        )
                        if (forced || row.custom) {
                            Spacer(Modifier.width(4.dp))
                            MeelanoIconButton(
                                iconRes = R.drawable.ic_trash,
                                contentDescription = stringResource(R.string.routing_reset_override),
                                onClick = {
                                    PerAppRouting.clearOverride(ctx, row.pkg)
                                    dirty = true
                                },
                                sizeDp = 34.dp,
                                corner = 11.dp,
                            )
                        }
                    }
                    PanelDivider()
                }
            }

            item {
                Text(
                    stringResource(
                        if (connected) R.string.routing_apply_connected else R.string.routing_apply_next,
                    ),
                    fontSize = 10.5.sp, color = p.faint, lineHeight = 16.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** One list row, already resolved: the sheet never re-decides what the policy decided. */
private data class RoutingRowModel(
    val pkg: String,
    val label: String,
    val cls: RouteClass,
    val custom: Boolean,
    val bypassed: Boolean,
)

/** The settings mirror of the policy, for the counter line in the header panel. */
private object RoutingRowDefaults {
    fun bypassCount(installed: Set<String>): Int =
        RoutingPolicy.effectiveBypass(
            installed = installed,
            forcedBypass = PerAppRouting.bypassSet,
            forcedTunnel = PerAppRouting.tunnelSet,
            enabled = PerAppRouting.enabled,
            autoBypass = PerAppRouting.autoBypass,
            self = "",
        ).size
}

/** Single-line text well in the sheet's own visual language (see FeedWell in SettingsSheet). */
@Composable
private fun RoutingField(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    ltr: Boolean = false,
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(p.shade(0.34f), p.well)), shape)
            .border(1.dp, p.tint(0.10f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = TextStyle(
                fontSize = 13.sp,
                color = p.text,
                lineHeight = 18.sp,
                textDirection = if (ltr) TextDirection.Ltr else TextDirection.Content,
            ),
            cursorBrush = SolidColor(p.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            Text(placeholder, fontSize = 12.sp, color = p.faint)
        }
    }
}

/** Category pill. Banking gets the accent so the safety promise is visible without reading. */
@Composable
private fun RoutingChip(text: String, highlight: Boolean) {
    val p = LocalPalette.current
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (highlight) p.accent.copy(alpha = 0.16f) else p.tint(0.09f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text,
            fontSize = 9.5.sp,
            color = if (highlight) p.accent else p.muted,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}
