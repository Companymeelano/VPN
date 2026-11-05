// Fetch the Xray core for Windows x64 into resources/core/ (xray.exe + geo*.dat).
// Runs on the windows-latest CI builder before electron-builder packs; a dev on Windows can
// run it locally too. The version is pinned so a release is reproducible: the desktop client
// speaks the same core dialect as the Android app (libXray v26.9.9 ↔ Xray-core v26 line).
//
// Never commit the extracted binaries — resources/core/ is git-ignored; the zip travels through
// the CI run only.
import { createWriteStream, existsSync, mkdirSync, rmSync } from "node:fs";
import { pipeline } from "node:stream/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import extract from "extract-zip";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const VERSION = "v26.10.29";
const URL = `https://github.com/XTLS/Xray-core/releases/download/${VERSION}/Xray-windows-64.zip`;
const OUT = join(ROOT, "resources", "core");
const ZIP = join(ROOT, "resources", `xray-${VERSION}-win64.zip`);

if (existsSync(join(OUT, "xray.exe"))) {
  console.log("core already present:", OUT);
  process.exit(0);
}

mkdirSync(OUT, { recursive: true });

// Three attempts with backoff: a transient CDN blip or a dropped connection on the runner is
// not a broken release — the old single-shot fetch made «build windows» flap for no real
// reason. A partial zip is always removed before the next attempt (and on final failure).
async function fetchOnce() {
  console.log("downloading", URL);
  const res = await fetch(URL, { redirect: "follow" });
  if (!res.ok) throw new Error(`download failed: ${res.status} ${res.statusText}`);
  await pipeline(res.body, createWriteStream(ZIP));
  await extract(ZIP, { dir: OUT });
  rmSync(ZIP, { force: true });
}

let lastError = null;
for (let attempt = 1; attempt <= 3; attempt++) {
  try {
    await fetchOnce();
    console.log("core extracted to", OUT);
    process.exit(0);
  } catch (e) {
    lastError = e;
    rmSync(ZIP, { force: true });
    console.error(`attempt ${attempt}/3 failed:`, e?.message ?? e);
    if (attempt < 3) await new Promise((r) => setTimeout(r, 2000 * attempt));
  }
}
console.error("core fetch failed after 3 attempts:", lastError?.message ?? lastError);
process.exit(1);
