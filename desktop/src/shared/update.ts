/**
 * Update checking for the desktop client — pure logic, no network, no Node.
 *
 * Why this cannot use `/releases/latest`: the repository's "Latest" badge belongs to the ANDROID
 * tag (v2.x.y) — a desktop updater pointed at it would forever claim "you are up to date" or offer
 * an APK. Desktop releases live under `desktop-v*` tags, so we scan the releases LIST and take the
 * newest desktop tag that is newer than the running version.
 */

export interface ReleaseAsset {
  name: string;
  size: number;
  browser_download_url: string;
}

export interface ReleaseEntry {
  tag_name: string;
  draft: boolean;
  prerelease: boolean;
  body?: string;
  assets: ReleaseAsset[];
}

export interface UpdateCheck {
  /** semver of the newer desktop release, e.g. "2.8.4" */
  version: string;
  /** e.g. "desktop-v2.8.4" */
  tag: string;
  /** direct URL of the x64 setup installer */
  setupUrl: string;
  /** direct URL of its .sha256 sidecar */
  sha256Url: string;
  /** asset size, bytes — shown before the download starts */
  sizeBytes: number;
  notes: string;
}

export function parseVersion(v: string): number[] {
  const parts = v.replace(/^desktop-v/i, "").replace(/^v/i, "").split(".").map((x) => {
    const n = parseInt(x, 10);
    return Number.isFinite(n) ? n : 0;
  });
  while (parts.length < 3) parts.push(0);
  return parts.slice(0, 3);
}

/** negative when a < b, 0 when equal, positive when a > b. "2.10.0" > "2.9.9" (numeric, not string). */
export function compareVersions(a: string, b: string): number {
  const pa = parseVersion(a);
  const pb = parseVersion(b);
  for (let i = 0; i < 3; i++) {
    const x = pa[i] ?? 0;
    const y = pb[i] ?? 0;
    if (x !== y) return x - y;
  }
  return 0;
}

/**
 * The newest `desktop-v*` release strictly newer than `currentVersion`, with its setup asset
 * and sha256 sidecar pinned by exact names — no "first .exe in the list" guessing.
 */
export function pickDesktopUpdate(releases: ReleaseEntry[], currentVersion: string): UpdateCheck | null {
  let best: { entry: ReleaseEntry; version: string } | null = null;
  for (const entry of releases) {
    if (entry.draft || entry.prerelease) continue;
    const m = /^desktop-v(\d+\.\d+\.\d+)$/.exec(entry.tag_name);
    const version = m?.[1];
    if (!version) continue;
    if (compareVersions(version, currentVersion) <= 0) continue;
    if (best && compareVersions(version, best.version) <= 0) continue;
    best = { entry, version };
  }
  if (!best) return null;

  const setupName = `MA-VPN-${best.version}-setup-x64.exe`;
  const setup = best.entry.assets.find((a) => a.name === setupName);
  const sha = best.entry.assets.find((a) => a.name === `${setupName}.sha256`);
  if (!setup || !sha) return null; // a half-published release must never trigger a broken update

  return {
    version: best.version,
    tag: best.entry.tag_name,
    setupUrl: setup.browser_download_url,
    sha256Url: sha.browser_download_url,
    sizeBytes: setup.size,
    notes: best.entry.body ?? "",
  };
}

/**
 * A `.sha256` sidecar is "<64-hex>[ *]<filename>" — parse the digest, ignore the name and any
 * other lines. Returns null when nothing digest-shaped is present.
 */
export function parseSha256File(text: string): string | null {
  for (const line of text.split(/\r?\n/)) {
    const m = /([a-fA-F0-9]{64})/.exec(line);
    const hex = m?.[1];
    if (hex) return hex.toLowerCase();
  }
  return null;
}
