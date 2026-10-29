/**
 * The tuning table the Android app carries (net/Regime.kt) — one dialect across platforms so a
 * node that works on the phone works on the desktop with the same shape.
 *
 *  CALM     - SNI filtering only. Cleanest transport wins; no fragmentation.
 *  TIGHT    - active RST injection + shape inspection. TLS ClientHello record splitting.
 *  BLACKOUT - wholesale port drops; the most aggressive split, plus no mux (muxed shapes are
 *             fingerprintable when almost nothing else is alive).
 *
 * Values are "not specified" defaults: 0 / "" means "let the core decide", never "off".
 */

export type RegimeId = "calm" | "tight" | "blackout";

export interface Tune {
  fragSize: number;
  fragCount: number;
  fragStrategy: string;
  fragDelayMs: number;
  mux: boolean;
  keepAliveSec: number;
  alpn: string;
  fingerprint: string;
}

const BASE: Tune = {
  fragSize: 0,
  fragCount: 1,
  fragStrategy: "",
  fragDelayMs: 0,
  mux: false,
  keepAliveSec: 0,
  alpn: "",
  fingerprint: "chrome",
};

export const REGIMES: Record<RegimeId, Tune> = {
  calm: { ...BASE, mux: true, keepAliveSec: 30, fragSize: 0 },
  tight: {
    ...BASE,
    fragSize: 200,
    fragCount: 2,
    fragStrategy: "variable",
    fragDelayMs: 20,
  },
  blackout: {
    ...BASE,
    fragSize: 100,
    fragCount: 3,
    fragStrategy: "variable",
    fragDelayMs: 35,
    mux: false,
    keepAliveSec: 15,
    alpn: "h2,http/1.1",
  },
};

export const REGIME_LABELS: Record<RegimeId, string> = {
  calm: "آرام",
  tight: "سفت",
  blackout: "سیاهه",
};

/** Unknown ids fall back to TIGHT — the safe default on today's Iranian networks. */
export function tuneFor(id: string | null | undefined): { id: RegimeId; tune: Tune } {
  const key: RegimeId =
    id === "calm" || id === "blackout" ? id : "tight";
  return { id: key, tune: REGIMES[key] };
}

/** True when the tune wants TLS ClientHello record splitting (only meaningful on TLS shapes). */
export function wantsFragment(tune: Tune, security: string): boolean {
  return tune.fragSize > 0 && security === "tls";
}
