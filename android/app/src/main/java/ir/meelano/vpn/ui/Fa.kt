package ir.meelano.vpn.ui

/**
 * The Persian numeric voice of the whole UI, in one place.
 *
 * Why this file exists: the app mixes hand-written Persian digits («ساعت ۶») with programmatic
 * numbers that used to render Latin («20735 روز پیش», «0/48», «2.2s») — and a mixed RTL/Latin
 * run without isolation scrambles on screen («زیر ۲۰۰ms» displayed as «زیرms۲۰۰»). Two rules:
 *
 *  1. every user-visible digit goes through [toFa];
 *  2. every run that mixes digits with Latin units or separators («۲۰۰ms», «۰/۴۸», «v2.8.2»)
 *     goes through [iso] — LRI…PDI (U+2066…U+2069) keeps the run visually intact inside RTL.
 *
 * Pure strings, no Compose: unit-testable and usable from data-row builders too.
 */

private val FA_DIGITS = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')

/** Latin digits → Persian digits. Everything else passes through untouched. */
internal fun toFa(s: String): String = buildString(s.length) {
    for (ch in s) append(if (ch in '0'..'9') FA_DIGITS[ch - '0'] else ch)
}

internal fun toFa(n: Long): String = toFa(n.toString())

internal fun toFa(n: Int): String = toFa(n.toString())

/**
 * Bidi isolation: wrap a mixed/Latin run so the RTL paragraph cannot re-order its parts.
 * "۰/۴۸" inside RTL would otherwise display as "۴۸/۰"; "۲۰۰ms" becomes "ms۲۰۰".
 */
internal fun iso(s: String): String = "\u2066" + s + "\u2069"

/** Convert AND isolate in one call — the right tool for a numeric run with Latin units. */
internal fun faIso(s: String): String = iso(toFa(s))
