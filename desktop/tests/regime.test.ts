import { describe, expect, it } from "vitest";
import { REGIMES, tuneFor, wantsFragment } from "../src/shared/regime";

describe("tuneFor", () => {
  it("maps known ids and falls back to tight for anything else", () => {
    expect(tuneFor("calm").id).toBe("calm");
    expect(tuneFor("blackout").id).toBe("blackout");
    expect(tuneFor("tight").id).toBe("tight");
    expect(tuneFor(null).id).toBe("tight");
    expect(tuneFor("nonsense").id).toBe("tight");
    expect(tuneFor(undefined).tune).toBe(REGIMES.tight);
  });
});

describe("wantsFragment", () => {
  it("only fragments TLS shapes and only when the tune says so", () => {
    expect(wantsFragment(REGIMES.tight, "tls")).toBe(true);
    expect(wantsFragment(REGIMES.tight, "reality")).toBe(false);
    expect(wantsFragment(REGIMES.tight, "none")).toBe(false);
    expect(wantsFragment(REGIMES.calm, "tls")).toBe(false);
    expect(wantsFragment(REGIMES.blackout, "tls")).toBe(true);
  });
});

describe("regime table", () => {
  it("blackout is strictly more aggressive than tight; calm never fragments", () => {
    expect(REGIMES.blackout.fragSize).toBeLessThan(REGIMES.tight.fragSize);
    expect(REGIMES.blackout.fragDelayMs).toBeGreaterThan(REGIMES.tight.fragDelayMs);
    expect(REGIMES.calm.fragSize).toBe(0);
    expect(REGIMES.calm.mux).toBe(true);
    expect(REGIMES.blackout.mux).toBe(false);
  });
});
