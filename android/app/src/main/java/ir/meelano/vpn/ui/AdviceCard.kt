package ir.meelano.vpn.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.meelano.vpn.BuildConfig
import ir.meelano.vpn.R
import ir.meelano.vpn.data.AppSettings
import ir.meelano.vpn.ui.theme.LocalPalette
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * "It didn't connect — what do I do?" in one card, under the control.
 *
 * Why the server writes the text and the client only maps the *action*: the useful sentence here is a
 * diagnosis of what filtering is doing tonight, which the phone cannot know alone (it sees one path;
 * the fleet sees the country — see docs/AI.md §۱). And the endpoint always answers: the model is a
 * polish layer over a canned table, so a dead API changes the wording, never the availability.
 *
 * Three rules this card obeys:
 *  - it never blocks the retry. Fetch is one GET, 2.5 s, and a failure means "no card", not "no VPN";
 *  - one answer per error signature per process (the map below). A user who retries five times must not
 *    send five requests — and must not be shouted at five times either;
 *  - the buttons apply *real* settings. A card that only explains is a poster: `fragment_on` writes
 *    `AppSettings.fragmentAuto`, `change_regime` writes `AppSettings.regime`, `switch_node` opens the
 *    list. Everything the advice can say, the app can do in one tap.
 */

private data class Advice(val title: String, val body: String, val action: String, val canned: Boolean)

private val adviceCache = ConcurrentHashMap<String, Advice>()
private val adviceClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()
}

private suspend fun fetchAdvice(err: String, proto: String, tier: String, regime: String): Advice? {
    val key = "$err|$proto|$tier|$regime"
    adviceCache[key]?.let { return it }
    return withContext(Dispatchers.IO) {
        runCatching {
            val url = BuildConfig.FEED_BASE_URL.trimEnd('/') +
                "/?action=advice&err=" + java.net.URLEncoder.encode(err, "UTF-8") +
                "&proto=$proto&tier=$tier&regime=$regime"
            val req = Request.Builder().url(url).header("X-Feed-Key", BuildConfig.MEELANO_FEED_KEY).build()
            adviceClient.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val o = JSONObject(r.body?.string() ?: return@use null).optJSONObject("advice") ?: return@use null
                Advice(
                    title = o.optString("title"),
                    body = o.optString("body"),
                    action = o.optString("action", "retry"),
                    canned = o.optBoolean("canned", true),
                ).also { adviceCache[key] = it }
            }
        }.getOrNull()
    }
}

/**
 * The action a card can perform, resolved to something the app owns. Unknown actions (a future server
 * that invents one) must degrade to "retry": an unhandled enum is a reason to hide the button, never a
 * reason to crash the screen the user is staring at.
 */
@Composable
fun AdviceCard(
    err: String,
    proto: String,
    tier: String,
    onSwitchNode: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val regime = AppSettings.effectiveRegime().name.lowercase()
    var advice by remember(err, proto, tier) { mutableStateOf<Advice?>(null) }
    LaunchedEffect(err, proto, tier, regime) { advice = fetchAdvice(err, proto, tier, regime) }
    val a = advice ?: return
    if (a.body.isBlank()) return

    AnimatedVisibility(visible = true, enter = fadeIn(), exit = fadeOut()) {
        val shape = RoundedCornerShape(16.dp)
        Column(
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .meeExtruded(corner = 16.dp, depth = 4.dp, shadowAlpha = 0.8f)
                .clip(shape)
                .background(p.surfaceHigh)
                // lit from above like every other face in the kit: `surfaceHigh` alone is a flat rectangle,
                // and a flat rectangle inside a card that has a side looks like a sticker put on afterwards
                .background(
                    Brush.verticalGradient(listOf(p.tint(0.05f), Color.Transparent, p.shade(0.14f))),
                    shape,
                )
                .border1(p)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    a.title.ifBlank { "اتصال برقرار نشد" },
                    fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = p.text,
                    modifier = Modifier.weight(1f),
                )
                if (!a.canned) {
                    Text(
                        "راهنما",
                        fontSize = 9.5.sp, color = p.accent, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(p.on(p.accent, 0.12f))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
            Text(a.body, fontSize = 12.sp, color = p.muted, lineHeight = 18.sp)
            val label: String? = when (a.action) {
                "fragment_on" -> "تکه‌تکه‌سازی را روشن کن"
                "fragment_off" -> "تکه‌تکه‌سازی را خاموش کن"
                "change_regime" -> if (regime == "blackout") "حالت را روی سنگین بگذار" else "حالت را روی «قطعی» بگذار"
                "switch_node" -> "یک سرور دیگر را امتحان کن"
                "wait" -> null                      // no button: the only honest action is doing nothing
                else -> "دوباره وصل شو"
            }
            if (label != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    MeelanoButton(
                        label = label,
                        onClick = {
                            when (a.action) {
                                "fragment_on" -> AppSettings.setFragmentAuto(ctx, true)
                                "fragment_off" -> AppSettings.setFragmentAuto(ctx, false)
                                "change_regime" -> AppSettings.setRegime(ctx, if (regime == "blackout") "tight" else "blackout")
                                "switch_node" -> onSwitchNode()
                                else -> onRetry()
                            }
                        },
                        tone = BtnTone.Tonal,
                        size = BtnSize.Small,
                    )
                    // the regime/fragment fixes are settings changes; say where they came from so the
                    // user can find them again (and undo them) instead of trusting a magic button
                    if (a.action == "fragment_on" || a.action == "fragment_off" || a.action == "change_regime") {
                        Text("در تنظیمات ▸ مقاومت", fontSize = 10.5.sp, color = p.faint)
                    }
                    Spacer(Modifier.width(0.dp))
                }
            }
        }
    }
}

/** the 1px edge, kept here rather than in the palette: this card is the only surface with an accent border */
private fun Modifier.border1(p: ir.meelano.vpn.ui.theme.Palette): Modifier =
    this.border(BorderStroke(1.dp, p.on(p.accent, 0.22f)), RoundedCornerShape(16.dp))
