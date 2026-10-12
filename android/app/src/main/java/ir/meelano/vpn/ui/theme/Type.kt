package ir.meelano.vpn.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import ir.meelano.vpn.R

/**
 * Two families, one job each:
 *  - Vazirmatn carries Persian UI text (it has real Persian shaping, no Latin fallback ugliness);
 *  - the system monospace family carries every number that moves (latency, speed, session, version).
 *    Without `tnum` the speed row re-flows twice a second and the whole app reads as "stuttering" -
 *    that is not decoration, it is the perception half of the freeze fix.
 */
object MeelanoType {

    val Sans = FontFamily(
        Font(R.font.vazirmatn_regular, FontWeight.Normal),
        Font(R.font.vazirmatn_medium, FontWeight.Medium),
        Font(R.font.vazirmatn_semi_bold, FontWeight.SemiBold),
        Font(R.font.vazirmatn_bold, FontWeight.Bold),
    )

    /**
     * Numbers get the system monospace family, not a bundled font: a monospace advance *is* tabular
     * by definition, so the speed row cannot re-flow, and it costs the APK 0 bytes. Shipping a fifth
     * display face to solve a problem `Monospace` already solves is the kind of decision that shows up
     * later as "why is this app 60 MB".
     */
    val Mono = FontFamily.Monospace

    private val align = TextStyle(lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None
    ))

    val Display = align.copy(fontFamily = Sans, fontSize = 34.sp, fontWeight = FontWeight.Bold,
        letterSpacing = (-0.6).sp, lineHeight = 42.sp)
    val Title = align.copy(fontFamily = Sans, fontSize = 22.sp, fontWeight = FontWeight.Bold,
        letterSpacing = (-0.35).sp, lineHeight = 30.sp)
    val Headline = align.copy(fontFamily = Sans, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp)
    val Body = align.copy(fontFamily = Sans, fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 26.sp)
    val Label = align.copy(fontFamily = Sans, fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp)
    val Caption = align.copy(fontFamily = Sans, fontSize = 12.sp, fontWeight = FontWeight.Normal,
        letterSpacing = 0.1.sp, lineHeight = 18.sp)

    /** the numeric style - use this for ANY value that can change while the screen is open */
    val Stat = align.copy(fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp, fontFeatureSettings = "tnum")
    val StatBig = align.copy(fontFamily = Mono, fontSize = 22.sp, fontWeight = FontWeight.Bold,
        letterSpacing = (-0.6).sp, fontFeatureSettings = "tnum")

    fun asMaterial3(): Typography = Typography(
        displaySmall = Display,
        headlineSmall = Title,
        titleLarge = Headline,
        bodyLarge = Body,
        bodyMedium = Body.copy(fontSize = 14.sp, lineHeight = 24.sp),
        titleMedium = Label,
        labelMedium = Caption,
    )
}
