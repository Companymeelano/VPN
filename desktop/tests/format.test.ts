import { describe, expect, it } from "vitest";
import { formatBytes, formatDuration, formatMs, formatSpeed, lri, toFa } from "../src/shared/format";

describe("toFa", () => {
  it("maps every Latin digit to its Persian twin (dots stay Latin: versions are Latin-shaped)", () => {
    expect(toFa("0123456789")).toBe("۰۱۲۳۴۵۶۷۸۹");
    expect(toFa("v2.8.1 (208001)")).toBe("v۲.۸.۱ (۲۰۸۰۰۱)");
  });
  it("passes non-digits through unchanged", () => {
    expect(toFa("abc-xyz")).toBe("abc-xyz");
  });
});

describe("lri (bidi isolation)", () => {
  it("wraps with LRI…PDI so an RTL paragraph cannot reorder the run", () => {
    expect(lri("10:30")).toBe("\u2066" + "10:30" + "\u2069");
  });
});

describe("formatBytes", () => {
  it("renders Persian units with one decimal above bytes", () => {
    expect(formatBytes(0)).toBe("۰ بایت");
    expect(formatBytes(512)).toBe("۵۱۲ بایت");
    expect(formatBytes(1024)).toBe("۱ کیلوبایت");
    expect(formatBytes(1536)).toBe("۱٫۵ کیلوبایت");
    expect(formatBytes(55_258_101)).toBe("۵۲٫۷ مگابایت");
    expect(formatBytes(2 * 1024 ** 3)).toBe("۲ گیگابایت");
  });
  it("clamps garbage to zero instead of rendering NaN", () => {
    expect(formatBytes(Number.NaN)).toBe("۰ بایت");
    expect(formatBytes(-5)).toBe("۰ بایت");
  });
  it("supports the en locale for Latin contexts", () => {
    expect(formatBytes(1536, "en")).toBe("1.5 KB");
  });
});

describe("formatMs", () => {
  it("sub-second in ms, above in seconds with Persian decimal separator", () => {
    expect(formatMs(120)).toBe("۱۲۰ میلی‌ثانیه");
    expect(formatMs(2400)).toBe("۲٫۴ ثانیه");
  });
});

describe("formatDuration", () => {
  it("renders mm:ss and h:mm:ss under bidi isolation", () => {
    expect(formatDuration(75)).toBe("\u2066" + "01:15" + "\u2069");
    expect(formatDuration(3671)).toBe("\u2066" + "1:01:11" + "\u2069");
    expect(formatDuration(-3)).toBe("\u2066" + "00:00" + "\u2069");
  });
});

describe("formatSpeed", () => {
  it("clamps counter resets to zero and appends the Persian unit", () => {
    expect(formatSpeed(0)).toBe("۰ بایت/ثانیه");
    expect(formatSpeed(-120)).toBe("۰ بایت/ثانیه");
    expect(formatSpeed(3_200_000)).toBe("۳٫۱ مگابایت/ثانیه");
  });
});
