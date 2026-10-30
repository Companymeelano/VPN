import { describe, expect, it } from "vitest";
import {
  compareVersions,
  parseSha256File,
  parseVersion,
  pickDesktopUpdate,
  type ReleaseEntry,
} from "../src/shared/update";

const rel = (tag: string, assets: [string, number][] = [], over: Partial<ReleaseEntry> = {}): ReleaseEntry => ({
  tag_name: tag,
  draft: false,
  prerelease: false,
  assets: assets.map(([name, size], i) => ({
    name,
    size,
    browser_download_url: `https://example.test/${i}/${name}`,
  })),
  ...over,
});

const setup = (v: string): [string, number][] => [
  [`MA-VPN-${v}-setup-x64.exe`, 95_000_000],
  [`MA-VPN-${v}-setup-x64.exe.sha256`, 94],
  [`MA-VPN-${v}-portable-x64.exe`, 94_000_000],
];

describe("version compare", () => {
  it("orders numerically, not as strings", () => {
    expect(compareVersions("2.10.0", "2.9.9")).toBeGreaterThan(0);
    expect(compareVersions("2.8.4", "2.8.4")).toBe(0);
    expect(compareVersions("2.8.3", "2.9.0")).toBeLessThan(0);
  });

  it("parses tags and bare versions the same", () => {
    expect(parseVersion("desktop-v2.8.4")).toEqual([2, 8, 4]);
    expect(parseVersion("v2.8")).toEqual([2, 8, 0]);
  });
});

describe("pickDesktopUpdate", () => {
  it("takes the newest desktop-v release and never the Android Latest tag", () => {
    const releases = [
      rel("v2.9.0", setup("2.9.0")), // Android release — must be ignored
      rel("desktop-v2.8.4", setup("2.8.4")),
      rel("desktop-v2.8.3", setup("2.8.3")),
    ];
    const up = pickDesktopUpdate(releases, "2.8.3");
    expect(up?.version).toBe("2.8.4");
    expect(up?.setupUrl).toContain("MA-VPN-2.8.4-setup-x64.exe");
    expect(up?.sha256Url).toContain(".sha256");
  });

  it("returns null when up to date", () => {
    expect(pickDesktopUpdate([rel("desktop-v2.8.3", setup("2.8.3"))], "2.8.3")).toBeNull();
    expect(pickDesktopUpdate([rel("desktop-v2.8.2", setup("2.8.2"))], "2.8.3")).toBeNull();
  });

  it("skips drafts, prereleases and half-published releases", () => {
    expect(pickDesktopUpdate([rel("desktop-v9.9.9", [], { draft: true })], "2.8.3")).toBeNull();
    expect(pickDesktopUpdate([rel("desktop-v9.9.9", [], { prerelease: true })], "2.8.3")).toBeNull();
    // setup present but no .sha256 sidecar → no update offered
    expect(
      pickDesktopUpdate([rel("desktop-v9.9.9", [["MA-VPN-9.9.9-setup-x64.exe", 1]])], "2.8.3"),
    ).toBeNull();
  });
});

describe("parseSha256File", () => {
  it("reads the digest and ignores the filename and extra lines", () => {
    const hex = "a".repeat(64);
    expect(parseSha256File(`${hex}  MA-VPN-2.8.4-setup-x64.exe\n`)).toBe(hex);
    expect(parseSha256File(`SHA256(file)= ${hex}\n`)).toBe(hex);
    expect(parseSha256File("garbage")).toBeNull();
  });
});
