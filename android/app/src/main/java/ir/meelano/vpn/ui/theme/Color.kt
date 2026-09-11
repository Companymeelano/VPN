package ir.meelano.vpn.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The single colour source for BOTH themes, and the only place a colour is ever written down.
 * `design/preview/index.html` carries the same values as CSS custom properties under
 * `:root` (dark) and `body.light` (light), and `res/values(-night)/colors.xml` carries them for the
 * parts Android paints (window background before the first frame, notification, QS tile, launcher
 * field). Three mirrors, one truth: the CI job `check-theme.sh` fails if they disagree.
 *
 * Rules this palette enforces:
 *  - ONE accent in the whole product; status colours are semantic, never decorative;
 *  - never pure black (OLED smearing + harsh at night), never pure-white text on white;
 *  - every text/interactive pair is >= 4.5:1, icons/glyphs >= 3:1 - in BOTH themes
 *    (checked, see docs/DESIGN-SYSTEM.md §14);
 *  - **no screen may hardcode a colour.** The old code read the dark object directly, which made the
 *    light theme half-work: M3 surfaces flipped, everything painted from `Meelano.*` stayed dark.
 *    Screens read `LocalPalette.current` instead, so a theme change is a recomposition, not a bug.
 *
 * Dark surfaces are lit from above (top edge lighter than bottom). Light surfaces are lit the same
 * way but the *medium* inverts: a raised face is white with a grey underside and a dark hairline,
 * a well is grey with a light inner top edge. Hence `raised*`/`bezel*` are palette roles, not
 * constants, and highlights go through `tint()` (white in dark, black in light) - a 6% white film is
 * invisible on white paper, which is what broke the buttons.
 */
@Immutable
data class Palette(
    val isDark: Boolean,
    // surfaces
    val bg: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val line: Color,
    val hairline: Color,
    val well: Color,
    val bezelTop: Color,
    val bezelBottom: Color,
    // text
    val text: Color,
    val muted: Color,
    val faint: Color,
    // semantic
    val accent: Color,
    val accentDeep: Color,
    val accentInk: Color,
    val info: Color,
    val warn: Color,
    val danger: Color,
    // control-kit roles (see Controls.kt)
    val raisedTop: Color,
    val raisedBottom: Color,
    val dangerTop: Color,
    val dangerBottom: Color,
    val dangerInk: Color,
    /** A's grade colour: the accent is 3.5:1 on white, so the *letter* needs a darker green. */
    val gradeA: Color,
) {
    fun grade(grade: String): Color = when (grade) {
        "A" -> gradeA
        "B" -> info
        "C" -> warn
        else -> danger
    }

    /** The highlight film: white in dark, black in light. Never use Color.White directly. */
    fun tint(alpha: Float): Color =
        (if (isDark) Color.White else Color.Black).copy(alpha = alpha)

    /** Shadows and ink hairlines. Light theme needs softer ones - black on white is heavy. */
    fun shade(alpha: Float): Color =
        Color(0xFF000000).copy(alpha = if (isDark) alpha else alpha * 0.6f)

    /** Glow. Coloured light only reads on a dark field; on paper it looks like a printing error. */
    fun halo(alpha: Float): Color = if (isDark) accent.copy(alpha = alpha) else Color.Transparent

    /** Same colour, forced alpha - for 10% accent washes etc. that must exist in both themes. */
    fun on(c: Color, alpha: Float): Color = c.copy(alpha = alpha)

    /** Ink that sits on top of colour `c` (a pill, a filled chip): dark ink on light mint, white on deep green. */
    fun inkOn(c: Color): Color = if (isDark) Color(0xFF04231A) else if (c == accent) Color.White else Color(0xFF0D151C)

    companion object {
        val Dark = Palette(
            isDark = true,
            bg = Color(0xFF0B0F14),
            surface = Color(0xFF131A21),
            surfaceHigh = Color(0xFF18212A),
            line = Color(0xFF232F3A),
            hairline = Color(0x0EFFFFFF),
            well = Color(0xFF080C11),
            bezelTop = Color(0xFF2A3540),
            bezelBottom = Color(0xFF0E141A),
            text = Color(0xFFE6EDF3),
            muted = Color(0xFF8FA3B8),
            faint = Color(0xFF5D7183),
            accent = Color(0xFF4ADE9B),
            accentDeep = Color(0xFF128C5A),
            accentInk = Color(0xFF04231A),
            info = Color(0xFF8BD9F5),
            warn = Color(0xFFF5A524),
            danger = Color(0xFFFF6B6B),
            raisedTop = Color(0xFFFFFFFF),
            raisedBottom = Color(0xFF000000),
            dangerTop = Color(0xFFFF7A7A),
            dangerBottom = Color(0xFFB3261E),
            dangerInk = Color(0xFFFF6B6B),
            gradeA = Color(0xFF4ADE9B),
        )

        val Light = Palette(
            isDark = false,
            bg = Color(0xFFF5F7F9),
            surface = Color(0xFFFFFFFF),
            surfaceHigh = Color(0xFFEDF1F5),
            line = Color(0xFFDDE4EA),
            hairline = Color(0x0E000000),
            well = Color(0xFFE9EEF3),
            bezelTop = Color(0xFFFFFFFF),
            bezelBottom = Color(0xFFCFD8E0),
            text = Color(0xFF0D151C),
            muted = Color(0xFF4B5C6B),
            faint = Color(0xFF607182),
            accent = Color(0xFF0E8F5B),
            accentDeep = Color(0xFF06693F),
            accentInk = Color(0xFFFFFFFF),
            info = Color(0xFF0B6C93),
            warn = Color(0xFF8A5200),
            danger = Color(0xFFC0362C),
            raisedTop = Color(0xFFFFFFFF),
            raisedBottom = Color(0xFFD9E1E8),
            dangerTop = Color(0xFFE0554A),
            dangerBottom = Color(0xFFA32B21),
            dangerInk = Color(0xFFA32B21),
            gradeA = Color(0xFF0A7349),
        )
    }
}

/** Screens read the palette through this; `MeelanoTheme` installs the right one for the mode. */
val LocalPalette = compositionLocalOf { Palette.Dark }
