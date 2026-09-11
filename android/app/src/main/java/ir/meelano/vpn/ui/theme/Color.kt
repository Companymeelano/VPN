package ir.meelano.vpn.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The single colour source. `design/preview/index.html` carries the identical values as CSS
 * custom properties, so the prototype and the app cannot drift: change one, change the other.
 *
 * Rules this palette enforces:
 *  - ONE accent in the whole product; status colours are semantic, never decorative;
 *  - never pure black (OLED smearing + harsh on the eye at night);
 *  - every text/interactive pair is >= 4.5:1, icons/glyphs >= 3:1 (checked, see docs/DESIGN-SYSTEM.md).
 */
object Meelano {
    // surfaces
    val Bg = Color(0xFF0B0F14)
    val Surface = Color(0xFF131A21)
    val SurfaceHigh = Color(0xFF18212A)
    val Line = Color(0xFF232F3A)
    val Hairline = Color(0x0EFFFFFF)          // white 5.5%
    val Well = Color(0xFF080C11)
    val BezelTop = Color(0xFF2A3540)
    val BezelBottom = Color(0xFF0E141A)

    // text
    val Text = Color(0xFFE6EDF3)              // 13.1:1 on Surface
    val Muted = Color(0xFF8FA3B8)             //  5.4:1
    val MutedFaint = Color(0xFF5D7183)        // captions only, never body text

    // semantic
    val Accent = Color(0xFF4ADE9B)
    val AccentDeep = Color(0xFF128C5A)
    val AccentInk = Color(0xFF04231A)         // text on accent
    val Info = Color(0xFF8BD9F5)
    val Warn = Color(0xFFF5A524)
    val Danger = Color(0xFFFF6B6B)

    /** grade -> colour. Colour is never the only signal: the letter + a shape ship with it. */
    fun grade(grade: String): Color = when (grade) {
        "A" -> Accent
        "B" -> Info
        "C" -> Warn
        else -> Danger
    }

    // light theme: same semantics, no gradient on the ring (gradients read dirty on white)
    val LBg = Color(0xFFF5F7F9)
    val LSurface = Color(0xFFFFFFFF)
    val LLine = Color(0xFFDDE4EA)
    val LText = Color(0xFF0D151C)
    val LMuted = Color(0xFF4B5C6B)
    val LAccent = Color(0xFF0E8F5B)
}
