/**
 * MA VPN for Windows — main process.
 *
 * Responsibilities, in order of importance:
 *  1. keep the tunnel honest (start/stop core, failover to the next node when it dies),
 *  2. keep the system proxy consistent with the tunnel (on connect, off on disconnect/quit),
 *  3. keep the window cheap (frameless custom titlebar, tray, no background thrash).
 *
 * Everything user-visible lives in the renderer; this file owns only system effects.
 */

import { app, BrowserWindow, ipcMain, Menu, nativeImage, safeStorage, shell, Tray } from "electron";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { XrayCore, fetchSubscription, tcpPing } from "./core";
import { setSystemProxy } from "./proxy";
import type { AppSettingsState, AppSnapshot, ConnectionState, SpeedPoint } from "../shared/contract";
import { parseSubscription, type NodeInfo } from "../shared/nodes";
import { tuneFor } from "../shared/regime";
import { candidateOrder, FailoverTracker, failoverDelayMs } from "../shared/failover";

const APP_VERSION = "2.8.2";
const DEFAULT_SETTINGS: AppSettingsState = {
  theme: "system",
  regime: "tight",
  systemProxy: true,
  autoStart: false,
  autoConnect: false,
  subscriptionUrl: "",
  lanAccess: true,
};

// ── persisted state ────────────────────────────────────────────────────────────
const userDataDir = app.getPath("userData");
mkdirSync(userDataDir, { recursive: true });
const settingsFile = join(userDataDir, "settings.json");
const nodesFile = join(userDataDir, "nodes.json");

function loadJson<T>(file: string, fallback: T): T {
  try {
    return { ...fallback, ...(JSON.parse(readFileSync(file, "utf-8")) as T) };
  } catch {
    return fallback;
  }
}

// The subscription URL is a credential (the VIP link rule: never public, never shared). At
// rest it is DPAPI-sealed via Electron safeStorage — the old plaintext `subscriptionUrl` key
// is read once and immediately migrated to `subscriptionUrlEnc`.
function loadSettings(): AppSettingsState {
  const raw = loadJson<Record<string, unknown>>(settingsFile, DEFAULT_SETTINGS as unknown as Record<string, unknown>);
  let url = typeof raw["subscriptionUrl"] === "string" ? (raw["subscriptionUrl"] as string) : "";
  const enc = raw["subscriptionUrlEnc"];
  if (typeof enc === "string" && enc) {
    try {
      url = safeStorage.decryptString(Buffer.from(enc, "base64"));
    } catch {
      /* keep whatever plaintext fallback existed */
    }
  }
  const { subscriptionUrlEnc: _drop, ...rest } = raw;
  void _drop;
  return { ...DEFAULT_SETTINGS, ...(rest as Partial<AppSettingsState>), subscriptionUrl: url };
}

const settings: AppSettingsState = loadSettings();
let nodes: NodeInfo[] = loadJson<NodeInfo[]>(nodesFile, []);
const pings = new Map<string, number>();

function saveSettings(): void {
  const { subscriptionUrl, ...rest } = settings;
  const out: Record<string, unknown> = { ...rest, subscriptionUrl: "" };
  if (subscriptionUrl) {
    try {
      if (safeStorage.isEncryptionAvailable()) {
        out["subscriptionUrlEnc"] = safeStorage.encryptString(subscriptionUrl).toString("base64");
      } else {
        out["subscriptionUrl"] = subscriptionUrl;
      }
    } catch {
      out["subscriptionUrl"] = subscriptionUrl;
    }
  }
  writeFileSync(settingsFile, JSON.stringify(out, null, 2), "utf-8");
}
function saveNodes(): void {
  writeFileSync(nodesFile, JSON.stringify(nodes, null, 2), "utf-8");
}

// ── runtime state ──────────────────────────────────────────────────────────────
let connection: ConnectionState = { status: "idle" };
let activeNodeId: string | null = null;
let connectedSince = 0;
const speed: SpeedPoint[] = [];
let reconnectTimer: NodeJS.Timeout | null = null;
/**
 * True only between "we turned the system proxy on" and "we turned it off". Quit cleanup
 * touches the proxy ONLY while this holds — a user who configured their own proxy must never
 * have it disabled by our exit path.
 */
let proxyOwned = false;

const core = new XrayCore(join(userDataDir, "profile"));

// ── window / tray ──────────────────────────────────────────────────────────────
let win: BrowserWindow | null = null;
let tray: Tray | null = null;

function createWindow(): void {
  win = new BrowserWindow({
    width: 1120,
    height: 740,
    minWidth: 900,
    minHeight: 620,
    frame: false,
    backgroundColor: settings.theme === "light" ? "#F2F5F0" : "#080D15",
    show: false,
    autoHideMenuBar: true,
    webPreferences: {
      preload: join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  });
  void win.loadFile(join(__dirname, "..", "renderer", "index.html"));
  win.once("ready-to-show", () => win?.show());
  // Closing to tray is the VPN convention: the tunnel keeps running, the taskbar entry leaves.
  win.on("close", (e) => {
    if (!quitting) {
      e.preventDefault();
      win?.hide();
    }
  });
}

function createTray(): void {
  const iconPath = join(__dirname, "..", "..", "resources", "icon", "icon.png");
  const fallback = join(app.getAppPath(), "build", "icon.png");
  const img = nativeImage.createFromPath(existsSync(iconPath) ? iconPath : fallback);
  tray = new Tray(img.isEmpty() ? nativeImage.createEmpty() : img);
  tray.setToolTip("MA VPN");
  tray.setContextMenu(
    Menu.buildFromTemplate([
      { label: "باز کردن MA VPN", click: () => (win?.show(), win?.focus()) },
      { type: "separator" },
      {
        label: "قطع اتصال",
        click: () => void doDisconnect(),
      },
      { type: "separator" },
      { label: "خروج کامل", click: () => (quitting = true, app.quit()) },
    ]),
  );
  tray.on("double-click", () => (win?.show(), win?.focus()));
}

let quitting = false;
let cleanedUp = false;

// Quit must CLEAN UP, not just raise a flag (the bug this replaces): Windows does not cascade
// process death, so an un-stopped xray.exe outlives the app as an invisible tunnel, and an
// untouched ProxyEnable=1 points every browser at a dead port. One async pass, then re-quit.
app.on("before-quit", (e) => {
  quitting = true;
  if (cleanedUp) return;
  e.preventDefault();
  cleanedUp = true;
  void (async () => {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    try {
      await core.stop();
    } catch {
      /* the forceKill exit hook is the backstop */
    }
    if (proxyOwned) {
      try {
        await setSystemProxy(false);
      } catch {
        /* leave it owned=false; the user can still fix it manually */
      }
      proxyOwned = false;
    }
    app.quit();
  })();
});

// Belt and braces: even a crash must not orphan xray.exe.
process.on("exit", () => core.forceKill());

// ── snapshot plumbing ──────────────────────────────────────────────────────────
function snapshot(): AppSnapshot {
  return {
    connection,
    settings,
    nodes,
    pings: Object.fromEntries(pings),
    activeNodeId,
    speed: speed.slice(-90),
    appVersion: APP_VERSION,
    coreVersion: "v26.10.29",
  };
}

function broadcast(): void {
  win?.webContents.send("app:snapshotChanged", snapshot());
}

// ── connection lifecycle ───────────────────────────────────────────────────────
const tracker = new FailoverTracker();
let consecutiveFailures = 0;
/** A "best" connect walks up to this many distinct candidates before giving up honestly. */
const MAX_WALK = 4;

/**
 * Attempt plan: a *specific* node is always tried (the user asked for it — a cooldown is a
 * chooser hint, not a veto), while "best" walks the non-cooled, ping-ranked candidates so one
 * dead node can never strand the whole connect.
 */
function attemptPlan(requested: string | "best"): NodeInfo[] {
  if (requested !== "best") {
    const n = nodes.find((x) => x.id === requested) ?? null;
    return n ? [n] : [];
  }
  return candidateOrder(nodes, pings, tracker).slice(0, MAX_WALK);
}

async function doConnect(requested: string | "best"): Promise<void> {
  if (nodes.length === 0) {
    connection = { status: "error", message: "ابتدا لینک اشتراک را در تنظیمات وارد کنید" };
    broadcast();
    return;
  }
  const plan = attemptPlan(requested);
  if (plan.length === 0) {
    connection = {
      status: "error",
      message: "همه‌ی سرورها تازه ناموفق بوده‌اند — چند لحظه دیگر دوباره تلاش کنید",
    };
    broadcast();
    return;
  }

  let lastError = "اتصال برقرار نشد";
  for (const node of plan) {
    connection = { status: "connecting", node: node.name, step: "آماده‌سازی هسته" };
    activeNodeId = node.id;
    broadcast();

    try {
      const { tune } = tuneFor(settings.regime);
      connection = { status: "connecting", node: node.name, step: "اتصال به سرور" };
      broadcast();
      await core.start(
        node,
        tune,
        {
          localPort: 10808,
          directDomains: [".ir"],
          allowLan: settings.lanAccess,
        },
        (code) => {
          // Unexpected death while connected → failover, not silence.
          if (!quitting && connection.status === "connected") {
            const reason = code === 0 ? "هسته بسته شد" : `هسته با کد ${code} متوقف شد`;
            void failover(node.id, reason);
          }
        },
      );
      // Give the core a moment to bind, then verify it is actually up.
      await new Promise((r) => setTimeout(r, 600));
      if (!core.isRunning()) throw new Error("core_exited_early");

      if (settings.systemProxy) {
        try {
          await setSystemProxy(true);
          proxyOwned = true;
        } catch {
          /* proxy is a convenience; the tunnel itself is the product */
        }
      }
      tracker.succeeded(node.id);
      consecutiveFailures = 0;
      connectedSince = Date.now();
      connection = {
        status: "connected",
        node: node.name,
        since: connectedSince,
        latencyMs: pings.get(node.id) ?? 0,
        localPort: 10808,
      };
      broadcast();
      return;
    } catch (err) {
      lastError = err instanceof Error ? err.message : "اتصال برقرار نشد";
      tracker.markFailed(node.id);
      await core.stop();
    }
  }
  connection = { status: "error", message: `اتصال برقرار نشد — ${lastError}` };
  broadcast();
}

async function failover(deadNodeId: string, reason: string): Promise<void> {
  tracker.markFailed(deadNodeId);
  consecutiveFailures++;
  const next = candidateOrder(nodes, pings, tracker)[0] ?? null;
  if (!next) {
    // The loop-stopper: every candidate is cooled. A truthful error beats an A↔B storm.
    consecutiveFailures = 0;
    connection = {
      status: "error",
      message: `${reason} — همه‌ی سرورهای جایگزین ناموفق بودند؛ دوباره تلاش کنید`,
    };
    broadcast();
    return;
  }
  connection = { status: "reconnecting", node: next.name, reason };
  broadcast();
  if (reconnectTimer) clearTimeout(reconnectTimer);
  reconnectTimer = setTimeout(() => void doConnect("best"), failoverDelayMs(consecutiveFailures));
}

async function doDisconnect(): Promise<void> {
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
  await core.stop();
  if (proxyOwned) {
    try {
      await setSystemProxy(false);
    } catch {
      /* best effort */
    }
    proxyOwned = false;
  }
  connection = { status: "idle" };
  activeNodeId = null;
  broadcast();
}

// ── pings / nodes ──────────────────────────────────────────────────────────────
async function testAllPings(): Promise<Record<string, number>> {
  const targets = nodes.slice(0, 24);
  const results = await Promise.all(
    targets.map(async (n) => [n.id, await tcpPing(n.host, n.port, 3000)] as const),
  );
  for (const [id, ms] of results) pings.set(id, ms);
  const out = Object.fromEntries(pings);
  broadcast();
  return out;
}

async function refreshNodes(): Promise<number> {
  if (!settings.subscriptionUrl.trim()) return nodes.length;
  const text = await fetchSubscription(settings.subscriptionUrl.trim());
  nodes = parseSubscription(text);
  saveNodes();
  broadcast();
  return nodes.length;
}

// ── speed meter (system-wide interface counters) ───────────────────────────────
let lastBytes: { at: number; total: number } | null = null;
let sampling = false;
const PS_COUNTER = `Get-Counter '\\Network Interface(*)\\Bytes Total/sec' -ErrorAction SilentlyContinue | ForEach-Object { $_.CounterSamples | Measure-Object -Property CookedValue -Sum | Select-Object -ExpandProperty Sum }`;

function sampleSpeed(): void {
  // PowerShell is not free: spawn it only when someone can see the chart (window open) or the
  // tunnel is up. The old unconditional 2 s poll kept a shell alive forever in the tray.
  if (!(win?.isVisible() ?? false) && connection.status !== "connected") return;
  if (sampling) return;
  sampling = true;
  const { spawn } = require("node:child_process") as typeof import("node:child_process");
  const child = spawn(
    "powershell.exe",
    ["-NoProfile", "-NonInteractive", "-Command", PS_COUNTER],
    { windowsHide: true, stdio: ["ignore", "pipe", "ignore"] },
  );
  let out = "";
  child.stdout.on("data", (d: Buffer) => (out += d.toString()));
  child.on("exit", () => {
    sampling = false;
    const value = Number(out.trim().replace(",", "."));
    if (Number.isFinite(value) && value >= 0) {
      const now = Date.now();
      if (lastBytes) {
        const dt = (now - lastBytes.at) / 1000;
        if (dt > 0) {
          speed.push({ t: now, down: value });
          if (speed.length > 180) speed.splice(0, speed.length - 90);
          broadcast();
        }
      }
      lastBytes = { at: now, total: value };
    }
  });
}

// ── IPC ────────────────────────────────────────────────────────────────────────
function wireIpc(): void {
  ipcMain.handle("app:snapshot", () => snapshot());
  ipcMain.handle("app:connect", (_e, nodeId: string) => doConnect(nodeId || "best"));
  ipcMain.handle("app:disconnect", () => doDisconnect());
  ipcMain.handle("app:refreshNodes", () => refreshNodes());
  ipcMain.handle("app:testPings", () => testAllPings());
  ipcMain.handle("app:settings", async (_e, patch: Partial<AppSettingsState>) => {
    Object.assign(settings, patch ?? {});
    saveSettings();
    app.setLoginItemSettings({ openAtLogin: settings.autoStart });
    // Proxy follows the switch live in both directions (the old code only ever disabled it,
    // so flipping the switch ON mid-session silently did nothing until the next connect).
    if (connection.status === "connected") {
      try {
        if (settings.systemProxy) {
          await setSystemProxy(true);
          proxyOwned = true;
        } else if (proxyOwned) {
          await setSystemProxy(false);
          proxyOwned = false;
        }
      } catch {
        /* best effort */
      }
    }
    if (settings.subscriptionUrl.trim()) {
      void refreshNodes().catch(() => {
        /* surfaced on next refresh click */
      });
    }
    broadcast();
    return settings;
  });
  ipcMain.on("app:openExternal", (_e, url: string) => {
    if (/^https?:\/\//i.test(url)) void shell.openExternal(url);
  });
  ipcMain.on("window:minimize", () => win?.minimize());
  ipcMain.on("window:close", () => win?.hide());
}

// ── boot ───────────────────────────────────────────────────────────────────────
// One instance, one tunnel: a second copy would fight the first over local port 10808 and
// the system proxy. A second launch just raises the existing window.
const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on("second-instance", () => {
    win?.show();
    win?.focus();
  });

  app.whenReady().then(() => {
    createWindow();
    createTray();
    wireIpc();
    app.setLoginItemSettings({ openAtLogin: settings.autoStart });
    setInterval(sampleSpeed, 2000);
    if (settings.subscriptionUrl.trim()) {
      void refreshNodes().catch(() => void 0);
      void testAllPings().catch(() => void 0);
    }
    if (settings.autoConnect && nodes.length > 0) {
      void doConnect("best");
    }
    app.on("activate", () => win?.show());
  });

  app.on("window-all-closed", () => {
    /* keep the tray and the tunnel alive on Windows — closing the window is not quitting */
  });
}
