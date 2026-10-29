import { defineConfig } from "vitest/config";

// Vitest gets its own config: vite.config.ts roots at src/renderer (the app shell), while the
// unit tests live in tests/ and exercise src/shared only — no DOM, no app root.
export default defineConfig({
  test: {
    root: ".",
    include: ["tests/**/*.test.ts"],
    environment: "node",
  },
});
