import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Renderer only. The Electron main process is bundled by scripts/build-main.mjs (esbuild),
// because Vite's node-side output is ESM and Electron's entry is CJS on Windows.
export default defineConfig({
  root: "src/renderer",
  base: "./",
  plugins: [react()],
  build: {
    outDir: "../../dist/renderer",
    emptyOutDir: true,
    target: "chrome128",
  },
  server: { port: 5178 },
});
