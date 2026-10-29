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
console.log("downloading", URL);
const res = await fetch(URL, { redirect: "follow" });
if (!res.ok) {
  console.error("download failed:", res.status, res.statusText);
  process.exit(1);
}
await pipeline(res.body, createWriteStream(ZIP));

// The zip carries xray.exe, geoip.dat, geosite.dat (and a couple of docs we do not need).
await extract(ZIP, { dir: OUT });
rmSync(ZIP, { force: true });
console.log("core extracted to", OUT);
