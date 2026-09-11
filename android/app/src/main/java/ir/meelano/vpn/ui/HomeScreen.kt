package ir.meelano.vpn.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.meelano.vpn.R
import ir.meelano.vpn.data.AppSettings
import ir.meelano.vpn.data.FeedNode
import ir.meelano.vpn.ui.theme.Meelano
import ir.meelano.vpn.update.UpdateManager
import ir.meelano.vpn.vpn.ConnectPhase
import kotlin.math.roundToInt

/**
 * The only screen. Why it is stacked like this (full reasoning in docs/DESIGN-SYSTEM.md):
 *
 *   [ ambient glow + control core ]   the whole upper half is ONE object: the thing you tap
 *   [ node chip ]                     what you are going through — one line, always visible
 *   [ banner slot ]                   update / notice: never a dialog that blocks the launch
 *   [ bottom row ]                    secondary actions, thumb height
 *
 * Nothing scrolls. A VPN home screen that scrolls is a VPN home screen with too much on it.
 * The glow, the ring and the status colour are the same three signals read at three distances:
 * from across the room you see the halo, at arm's length the ring, close up the words.
 */
@Composable
fun HomeScreen(vm: VpnViewModel) {
    val phase by vm.phase.collectAsState()
    val traffic by vm.traffic.collectAsState()
    val vip by vm.vip.collectAsState()
    val free by vm.free.collectAsState()
    val activeId by vm.activeId.collectAsState()
    val syncing by vm.syncing.collectAsState()
    val update by vm.updateState.collectAsState()
    val openServersFlag by vm.openServers.collectAsState()

    val ctx = LocalContext.current
    var servers by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var showUpdate by remember { mutableStateOf(false) }
    var onboard by remember { mutableStateOf(!AppSettings.onboardingDone) }

    LaunchedEffect(openServersFlag) {
        if (openServersFlag) {
            servers = true
            vm.consumeOpenServers()
        }
    }

    val node = (vip + free).firstOrNull { it.id == activeId } ?: vm.bestAuto()
    val connected = phase is ConnectPhase.Connected
    val busy = phase !is ConnectPhase.Idle && phase !is ConnectPhase.Connected && phase !is ConnectPhase.Failed
    val available = (update as? UpdateManager.State.Available)?.info
    val bannerVisible = available != null && available.versionCode != AppSettings.skippedVersion

    Scaffold(contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)) { _ ->
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            AmbientGlow(
                accent = when {
                    phase is ConnectPhase.Failed -> Meelano.Danger
                    connected -> Meelano.Accent
                    busy -> Meelano.Info
                    else -> Meelano.Accent
                },
                intensity = when {
                    connected -> 0.17f
                    busy -> 0.12f
                    else -> 0.06f
                },
            )

            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TopBar(connected = connected, onSettings = { settings = true })

                VpnControlPanel(
                    phase = phase,
                    traffic = traffic,
                    node = node,
                    onToggle = { vm.toggle() },
                    onOpenSheet = { servers = true },
                )

                Spacer(Modifier.height(6.dp))
                NodeChip(
                    label = if (AppSettings.autoSelect && node != null && activeId == null) {
                        stringResource(R.string.seg_auto)
                    } else if (node == null) {
                        stringResource(R.string.empty_vip_title)
                    } else {
                        "${node.name} · ${node.countryFa()}"
                    },
                    onClick = { servers = true },
                )

                Spacer(Modifier.weight(1f))

                AnimatedVisibility(
                    visible = bannerVisible && available != null,
                    enter = fadeIn() + expandVertically(tween(200)),
                    exit = fadeOut() + shrinkVertically(tween(150)),
                ) {
                    UpdateBanner(
                        version = available?.versionName ?: "",
                        size = humanSize(available?.sizeBytes ?: 0),
                        onOpen = { showUpdate = true },
                        onSkip = { available?.let { AppSettings.setSkippedVersion(ctx, it.versionCode) } },
                    )
                }

                BottomRow(
                    nodes = vip.size + free.size,
                    syncing = syncing,
                    onServers = { servers = true },
                    onSettings = { settings = true },
                )
            }
        }
    }

    if (servers) {
        ServerListSheet(
            vm = vm,
            vip = vip,
            free = free,
            activeId = activeId,
            syncing = syncing,
            onDismiss = { servers = false },
        )
    }
    if (settings) SettingsSheet(onDismiss = { settings = false })
    if (showUpdate) UpdateSheet(vm, onDismiss = { showUpdate = false })
    if (onboard) OnboardingScreen(onDone = { AppSettings.setOnboardingDone(ctx); onboard = false })
}

/** the mark, the name, and a live state light — the app is readable from the top bar alone */
@Composable
private fun TopBar(connected: Boolean, onSettings: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_launcher_monochrome),
            stringResource(R.string.app_short),
            Modifier.size(20.dp),
            tint = Meelano.Accent,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(R.string.app_short),
            color = Meelano.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.1.sp,
        )
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(Color.White.copy(alpha = 0.05f)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (connected) Meelano.Accent else Meelano.MutedFaint)
            )
        }
        Spacer(Modifier.size(8.dp))
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onSettings),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_settings),
                stringResource(R.string.settings),
                Modifier.size(20.dp),
                tint = Meelano.Muted,
            )
        }
    }
}

@Composable
private fun NodeChip(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Meelano.Text, fontSize = 13.sp)
        Spacer(Modifier.size(7.dp))
        Text("▾", color = Meelano.MutedFaint, fontSize = 11.sp)
    }
}

@Composable
private fun BottomRow(nodes: Int, syncing: Boolean, onServers: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (syncing) "…" else stringResource(R.string.set_server_count, nodes),
            color = Meelano.MutedFaint, fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onServers)
                .padding(4.dp),
        )
        Text(
            stringResource(R.string.settings),
            color = Meelano.MutedFaint, fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onSettings)
                .padding(4.dp),
        )
    }
}

/**
 * One ambient light source. A drawn radial gradient, not a bitmap halo: it follows the state colour,
 * costs 0 bytes of APK, and cannot seam on a 2.75" phone or a 7" tablet. Static when reduced motion
 * is on — the colour still carries the state, only the breathing stops.
 */
@Composable
private fun AmbientGlow(accent: Color, intensity: Float) {
    val reduced = AppSettings.reducedMotion
    val t = rememberInfiniteTransition(label = "glow")
    val pulse by t.animateFloat(0.72f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Reverse), label = "pulse")
    val k = if (reduced) 1f else pulse
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            Brush.radialGradient(
                colors = listOf(accent.copy(alpha = intensity * k), Color.Transparent),
                center = Offset(size.width / 2f, size.height * 0.32f),
                radius = size.minDimension * 0.95f,
            )
        )
    }
}

@Composable
private fun UpdateBanner(version: String, size: String, onOpen: () -> Unit, onSkip: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Meelano.Accent.copy(alpha = 0.10f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Meelano.Accent))
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.upd_title), color = Meelano.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("v$version · $size", color = Meelano.Muted, fontSize = 11.sp)
        }
        Text(
            stringResource(R.string.upd_now),
            color = Meelano.AccentDeep, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onOpen)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Spacer(Modifier.size(6.dp))
        Text(
            stringResource(R.string.upd_skipping),
            color = Meelano.MutedFaint, fontSize = 11.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onSkip)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

internal fun humanSize(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes < 1024 -> "$bytes B"
    bytes < 1048576 -> String.format("%.0f KB", bytes / 1024.0)
    else -> String.format("%.1f MB", bytes / 1048576.0)
}

/** connect-stage percentage; the Persian percent sign, because this sits inside Persian copy */
internal fun pct(v: Float): String = (v.coerceIn(0f, 1f) * 100).roundToInt().toString() + "٪"

/** the feed ships only a 2-letter code; the app owns the Persian country names (never the vendor's) */
internal fun FeedNode.countryFa(): String = cc?.trim()?.uppercase()?.let { CcFa.of(it) } ?: "—"

private object CcFa {
    private val m = mapOf(
        "DE" to "آلمان", "NL" to "هلند", "FR" to "فرانسه", "GB" to "انگلستان", "UK" to "انگلستان",
        "SE" to "سوئد", "FI" to "فنلاند", "NO" to "نروژ", "DK" to "دانمارک", "IS" to "ایسلند",
        "US" to "آمریکا", "CA" to "کانادا", "MX" to "مکزیک", "TR" to "ترکیه", "AE" to "امارات",
        "AT" to "اتریش", "CH" to "سوئیس", "BE" to "بلژیک", "IT" to "ایتالیا", "ES" to "اسپانیا",
        "PT" to "پرتغال", "GR" to "یونان", "CZ" to "چک", "PL" to "لهستان", "HU" to "مجارستان",
        "RO" to "رومانی", "BG" to "بلغارستان", "RS" to "صربستان", "HR" to "کرواسی", "SI" to "اسلوونی",
        "IE" to "ایرلند", "LU" to "لوکزامبورگ", "LV" to "لتونی", "LT" to "لیتوانی", "EE" to "استونی",
        "UA" to "اوکراین", "RU" to "روسیه", "BY" to "بلاروس", "MD" to "مولداوی", "IN" to "هند",
        "PK" to "پاکستان", "SG" to "سنگاپور", "MY" to "مالزی", "ID" to "اندونزی", "TH" to "تایلند",
        "VN" to "ویتنام", "PH" to "فیلیپین", "JP" to "ژاپن", "KR" to "کره جنوبی", "HK" to "هنگ‌کنگ",
        "TW" to "تایوان", "CN" to "چین", "AU" to "استرالیا", "NZ" to "نیوزیلند", "BR" to "برزیل",
        "AR" to "آرژانتین", "CL" to "شیلی", "CO" to "کلمبیا", "PE" to "پرو", "ZA" to "آفریقای جنوبی",
        "EG" to "مصر", "MA" to "مراکش", "TN" to "تونس", "DZ" to "الجزایر", "LY" to "لیبی",
        "KE" to "کنیا", "NG" to "نیجریه", "GH" to "غنا", "ET" to "اتیوپی",
        "KZ" to "قزاقستان", "UZ" to "ازبکستان", "TM" to "ترکمنستان", "KG" to "قرقیزستان", "TJ" to "تاجیکستان",
        "AM" to "ارمنستان", "GE" to "گرجستان", "AZ" to "آذربایجان", "IQ" to "عراق", "IR" to "ایران",
        "QA" to "قطر", "OM" to "عمان", "BH" to "بحرین", "KW" to "کویت", "JO" to "اردن", "LB" to "لبنان",
        "SY" to "سوریه", "IL" to "فلسطین اشغالی", "SA" to "عربستان", "YE" to "یمن", "CY" to "قبرس",
        "MT" to "مالت", "SK" to "اسلواکی", "AL" to "آلبانی", "BA" to "بوسنی", "MK" to "مقدونیه",
    )
    fun of(cc: String): String = m[cc] ?: cc
}
