// Bundle the Electron main process (and preload) to CJS with esbuild.
// Electron's entry on Windows is CommonJS; externalising "electron" keeps the binary out.
import { build } from "esbuild";
import { mkdirSync } from "node:fs";

mkdirSync("dist/main", { recursive: true });

const common = {
  bundle: true,
  platform: "node",
  target: "node20",
  format: "cjs",
  external: ["electron"],
  sourcemap: false,
  logLevel: "info",
};

await build({ ...common, entryPoints: ["src/main/main.ts"], outfile: "dist/main/main.js" });
await build({ ...common, entryPoints: ["src/main/preload.ts"], outfile: "dist/main/preload.js" });
