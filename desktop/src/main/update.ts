/**
 * In-app updater for the desktop client: check GitHub for the newest `desktop-v*` release,
 * download the setup installer into the user's Downloads folder, verify its `.sha256` sidecar,
 * then run it (the NSIS wizard upgrades in place) and quit.
 *
 * Three deliberate choices:
 *  - the file lands in `app.getPath("downloads")`, not a hidden cache: the user asked for the
 *    installer to live in their own storage — it survives uninstalls and doubles as the
 *    «نصب مجدد» copy;
 *  - verification before install: a corrupt or truncated download must never reach the wizard;
 *  - the installer is spawned, then the app quits immediately — NSIS cannot replace our files
 *    while we hold them (EBUSY), and quitting first is what makes «upgrade in place» work.
 */

import { createWriteStream } from "node:fs";
import { unlink } from "node:fs/promises";
import { spawn } from "node:child_process";
import { app, shell } from "electron";
import { get as httpsGet } from "node:https";
import { compareVersions, parseSha256File, pickDesktopUpdate, type ReleaseEntry, type UpdateCheck } from "../shared/update";

const REPO = "Companymeelano/VPN";
const APP_VERSION = "2.8.4";

let cached: { at: number; value: UpdateCheck | null } | null = null;
let savedPath: string | null = null;
const CACHE_MS = 30 * 60_000; // a release check is cheap but not free; half an hour is fresh enough

function fetchJson(url: string): Promise<ReleaseEntry[]> {
  return new Promise((resolve, reject) => {
    const req = httpsGet(
      url,
      { headers: { "User-Agent": `MA-VPN-Desktop/${APP_VERSION}`, Accept: "application/vnd.github+json" } },
      (res) => {
        if (res.statusCode && res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          res.resume();
          fetchJson(res.headers.location).then(resolve, reject);
          return;
        }
        if (res.statusCode !== 200) {
          res.resume();
          reject(new Error(`http_${res.statusCode}`));
          return;
        }
        let body = "";
        res.setEncoding("utf8");
        res.on("data", (c) => (body += c));
        res.on("end", () => {
          try {
            resolve(JSON.parse(body) as ReleaseEntry[]);
          } catch {
            reject(new Error("bad_json"));
          }
        });
      },
    );
    req.setTimeout(12_000, () => req.destroy(new Error("timeout")));
    req.on("error", reject);
  });
}

function fetchText(url: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const req = httpsGet(url, { headers: { "User-Agent": `MA-VPN-Desktop/${APP_VERSION}` } }, (res) => {
      if (res.statusCode && res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        res.resume();
        fetchText(res.headers.location).then(resolve, reject);
        return;
      }
      if (res.statusCode !== 200) {
        res.resume();
        reject(new Error(`http_${res.statusCode}`));
        return;
      }
      let body = "";
      res.setEncoding("utf8");
      res.on("data", (c) => (body += c));
      res.on("end", () => resolve(body));
    });
    req.setTimeout(12_000, () => req.destroy(new Error("timeout")));
    req.on("error", reject);
  });
}

export async function checkForUpdate(): Promise<UpdateCheck | null> {
  if (cached && Date.now() - cached.at < CACHE_MS) return cached.value;
  const releases = await fetchJson(`https://api.github.com/repos/${REPO}/releases?per_page=30`);
  const value = pickDesktopUpdate(releases, app.getVersion());
  cached = { at: Date.now(), value };
  return value;
}

/** Drops the cache so the «بررسی نسخه» button always asks the server again. */
export function invalidateUpdateCache(): void {
  cached = null;
}

export function currentAppVersion(): string {
  return app.getVersion();
}

export { compareVersions };

export async function downloadUpdate(
  onProgress: (percent: number, bytes: number, total: number) => void,
): Promise<string> {
  invalidateUpdateCache();
  const check = await checkForUpdate();
  if (!check) throw new Error("no_update");
  const target = `${app.getPath("downloads")}/${`MA-VPN-${check.version}-setup-x64.exe`}`;

  await new Promise<void>((resolve, reject) => {
    const req = httpsGet(check.setupUrl, { headers: { "User-Agent": `MA-VPN-Desktop/${APP_VERSION}` } }, (res) => {
      if (res.statusCode && res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        res.resume();
        downloadTo(target, res.headers.location, check.sizeBytes, onProgress).then(resolve, reject);
        return;
      }
      if (res.statusCode !== 200) {
        res.resume();
        reject(new Error(`http_${res.statusCode}`));
        return;
      }
      const out = createWriteStream(target);
      let done = 0;
      res.on("data", (chunk: Buffer) => {
        done += chunk.length;
        if (check.sizeBytes > 0 && done > check.sizeBytes * 1.02 + 1024) {
          req.destroy(new Error("download_too_large"));
          return;
        }
        onProgress(check.sizeBytes > 0 ? Math.min(100, Math.floor((done * 100) / check.sizeBytes)) : 0, done, check.sizeBytes);
      });
      res.pipe(out);
      out.on("finish", () => out.close(() => resolve()));
      out.on("error", reject);
    });
    req.setTimeout(30_000, () => req.destroy(new Error("timeout")));
    req.on("error", reject);
  });

  // verify against the release's own .sha256 sidecar before anything may run
  const sidecar = await fetchText(check.sha256Url);
  const expected = parseSha256File(sidecar);
  const actual = await sha256File(target);
  if (!expected || expected !== actual) {
    await unlink(target).catch(() => undefined);
    throw new Error("checksum_mismatch");
  }
  savedPath = target;
  return target;
}

function downloadTo(
  target: string,
  url: string,
  total: number,
  onProgress: (percent: number, bytes: number, total: number) => void,
): Promise<void> {
  return new Promise((resolve, reject) => {
    const req = httpsGet(url, { headers: { "User-Agent": `MA-VPN-Desktop/${APP_VERSION}` } }, (res) => {
      if (res.statusCode !== 200) {
        res.resume();
        reject(new Error(`http_${res.statusCode}`));
        return;
      }
      const out = createWriteStream(target);
      let done = 0;
      res.on("data", (chunk: Buffer) => {
        done += chunk.length;
        onProgress(total > 0 ? Math.min(100, Math.floor((done * 100) / total)) : 0, done, total);
      });
      res.pipe(out);
      out.on("finish", () => out.close(() => resolve()));
      out.on("error", reject);
    });
    req.setTimeout(30_000, () => req.destroy(new Error("timeout")));
    req.on("error", reject);
  });
}

export async function installUpdate(): Promise<void> {
  const path = savedPath;
  if (!path) throw new Error("not_downloaded");
  // spawn the NSIS wizard and get out of the way — holding our own files open makes the
  // installer fail mid-copy ("upgrade in place" needs the app gone first).
  spawn(path, [], { detached: true, stdio: "ignore" }).unref();
  setTimeout(() => app.quit(), 400);
}

export async function openUpdateFile(): Promise<void> {
  const path = savedPath;
  if (path) shell.showItemInFolder(path);
}

async function sha256File(path: string): Promise<string> {
  const { createHash } = await import("node:crypto");
  const { createReadStream } = await import("node:fs");
  return new Promise((resolve, reject) => {
    const h = createHash("sha256");
    const s = createReadStream(path);
    s.on("data", (c) => h.update(c));
    s.on("end", () => resolve(h.digest("hex")));
    s.on("error", reject);
  });
}
