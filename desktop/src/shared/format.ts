/**
 * Presentation helpers shared by every screen.
 *
 * The bidi trap this file owns (and the tests pin): Persian UI text is RTL, but timestamps,
 * byte counts and hostnames arrive as Latin digits and symbols. Pasting those raw into RTL
 * paragraphs makes Windows re-order them ("10:30" becomes "30:10" visually). The fix is the
 * standard Unicode one: wrap a Latin run in LRI…PDI (U+2066…U+2069) so the bidi algorithm
 * treats it as one neutral-direction island. Persian digits (۰-۹) never need isolation —
 * digits are weak-RTL-friendly — so we convert where the design wants «فارسی» and isolate
 * where we deliberately keep Latin (versions, hosts, hashes).
 */

const FA_DIGITS = ["۰", "۱", "۲", "۳", "۴", "۵", "۶", "۷", "۸", "۹"];

/** Latin digits → Persian digits (decimal separator stays as given, usually "." or "٫"). */
export function toFa(input: string | number): string {
  return String(input).replace(/[0-9]/g, (d) => FA_DIGITS[Number(d)] ?? d);
}

const LRI = "\u2066";
const PDI = "\u2069";

/** Isolate a Latin/neutral fragment so RTL text around it never re-orders it. */
export function lri(fragment: string): string {
  return LRI + fragment + PDI;
}

/** Human byte size: 1.2 گیگابایت / 340 کیلوبایت. Persian digits, Latin unit prefix kept readable. */
export function formatBytes(bytes: number, locale: "fa" | "en" = "fa"): string {
  if (!Number.isFinite(bytes) || bytes < 0) return locale === "fa" ? "۰ بایت" : "0 B";
  const units = ["بایت", "کیلوبایت", "مگابایت", "گیگابایت", "ترابایت"];
  const unitsEn = ["B", "KB", "MB", "GB", "TB"];
  let value = bytes;
  let i = 0;
  while (value >= 1024 && i < units.length - 1) {
    value /= 1024;
    i++;
  }
  const rounded = i === 0 ? Math.round(value) : Math.round(value * 10) / 10;
  if (locale === "en") return `${rounded} ${unitsEn[i]}`;
  // Persian decimal separator is «٫» (U+066B) — a Latin "." next to Persian digits reads as
  // thousands-separator noise in RTL and Windows renders it at the wrong edge of the number.
  return `${toFa(String(rounded).replace(".", "٫"))} ${units[i]}`;
}

/** ms → «۱۲۰ میلی‌ثانیه» / «۲٫۴ ثانیه». */
export function formatMs(ms: number, locale: "fa" | "en" = "fa"): string {
  if (!Number.isFinite(ms) || ms < 0) return locale === "fa" ? "۰" : "0";
  if (ms < 1000) {
    const v = Math.round(ms);
    return locale === "fa" ? `${toFa(v)} میلی‌ثانیه` : `${v} ms`;
  }
  const s = Math.round(ms / 100) / 10;
  return locale === "fa" ? `${toFa(String(s).replace(".", "٫"))} ثانیه` : `${s} s`;
}

/** Seconds → mm:ss (or h:mm:ss) with Persian digits and bidi isolation for the colon shape. */
export function formatDuration(totalSeconds: number, locale: "fa" | "en" = "fa"): string {
  const s = Math.max(0, Math.floor(totalSeconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  const pad = (n: number) => String(n).padStart(2, "0");
  const clock = h > 0 ? `${h}:${pad(m)}:${pad(sec)}` : `${pad(m)}:${pad(sec)}`;
  return locale === "fa" ? lri(clock) : clock;
}

/** Speed → «۳٫۲ مگابایت/ثانیه». Negative/NaN clamps to zero (a counter reset must not render «-…»). */
export function formatSpeed(bytesPerSec: number, locale: "fa" | "en" = "fa"): string {
  const v = Number.isFinite(bytesPerSec) && bytesPerSec > 0 ? bytesPerSec : 0;
  return locale === "en" ? `${formatBytes(v, "en")}/s` : `${formatBytes(v, "fa")}/ثانیه`;
}
