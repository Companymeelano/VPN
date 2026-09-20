package ir.meelano.vpn.ui

import ir.meelano.vpn.ui.theme.LocalPalette

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.meelano.vpn.BuildConfig
import ir.meelano.vpn.R
import ir.meelano.vpn.data.AppSettings
import ir.meelano.vpn.data.Diagnostics
import ir.meelano.vpn.vpn.ConnectPhase

/**
 * "Send me the report" - the self-test card as one sheet, one tap to copy, one tap to share.
 *
 * Why it lives next to the failure state and not in an "about" page: the only moment a user wants a
 * diagnostic dump is when something broke, and the moment it broke is the moment their memory of the
 * app is being formed. Screenshots of the error toast are useless to whoever operates the fleet; this
 * text is the same size and 40x more useful.
 *
 * Everything privacy-related is decided in [Diagnostics.render] (a flat input that cannot hold a host,
 * plus redaction over free-form fields), so this file has exactly one job: present the string and give
 * it away. The text is selectable on purpose - some people screenshot it, some paste it, and a sheet
 * that only offers a button is a sheet that loses the tail of a long report.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsSheet(
    vm: VpnViewModel,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val phase by vm.phase.collectAsState()
    val traffic by vm.traffic.collectAsState()
    val vip by vm.vip.collectAsState()
    val free by vm.free.collectAsState()

    val node = vm.activeNode ?: vm.bestAuto()
    val error = (phase as? ConnectPhase.Failed)?.reason ?: ""
    val report = remember(error, traffic, node, vip.size + free.size, AppSettings.lastBlockReport,
        AppSettings.regime, AppSettings.mtu) {
        Diagnostics.render(
            Diagnostics.Input(
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                buildId = BuildConfig.BUILD_ID,
                channel = BuildConfig.CHANNEL,
                coreLinked = BuildConfig.CORE_LINKED,
                coreEngine = BuildConfig.CORE_ENGINE,
                regime = AppSettings.regime,
                detectedRegime = AppSettings.detectedRegime,
                mtu = AppSettings.mtu,
                fragmentAuto = AppSettings.fragmentAuto,
                muxEnabled = AppSettings.muxEnabled,
                killSwitch = AppSettings.killSwitch,
                secureDns = AppSettings.secureDns,
                nodeLabel = node?.name ?: "",
                nodeGrade = node?.grade ?: "",
                nodeLatencyMs = node?.latencyMs ?: -1L,
                connectedSeconds = traffic.seconds,
                rxBytes = traffic.rx,
                txBytes = traffic.tx,
                error = error,
                blockReport = AppSettings.lastBlockReport,
                feedCount = vip.size + free.size,
                feedSource = vm.feedSourceLabel(),
                coreBridge = ir.meelano.vpn.vpn.XrayBridge.describe(),
            )
        )
    }
    var done by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = p.surface,
        contentColor = p.text,
        dragHandle = null,
    ) {
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
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.diag_title),
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = p.text,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.diag_lines, report.lineSequence().count()),
                    fontSize = 10.5.sp, color = p.faint,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                )
            }

            SelectionContainer(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 10.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(p.well)
                    .padding(13.dp)
                    .heightIn(max = 340.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    report,
                    fontSize = 11.5.sp, lineHeight = 18.sp, color = p.text,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MeelanoButton(
                    label = stringResource(R.string.diag_copy),
                    onClick = {
                        runCatching {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("meelano-diagnostics", report))
                            done = ctx.getString(R.string.diag_copied)
                        }
                    },
                    tone = BtnTone.Tonal,
                    size = BtnSize.Small,
                    modifier = Modifier.weight(1f),
                )
                MeelanoButton(
                    label = stringResource(R.string.diag_share),
                    onClick = {
                        runCatching {
                            val send = Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, report)
                            ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.diag_title)))
                        }.onFailure { done = ctx.getString(R.string.diag_no_share) }
                    },
                    tone = BtnTone.Ghost,
                    size = BtnSize.Small,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                done.ifBlank { stringResource(R.string.diag_note) },
                fontSize = 10.5.sp, lineHeight = 15.sp, color = p.faint,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(18.dp))
        }
    }
}
