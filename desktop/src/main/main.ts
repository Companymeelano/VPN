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

import { app, BrowserWindow, ipcMain, Menu, nativeImage, shell, Tray } from "electron";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { XrayCore, fetchSubscription, tcpPing } from "./core";
import { setSystemProxy } from "./proxy";
import type { AppSettingsState, AppSnapshot, ConnectionState, SpeedPoint } from "../shared/contract";
import { parseSubscription, rankByPing, type NodeInfo } from "../shared/nodes";
import { tuneFor } from "../shared/regime";

const APP_VERSION = "2.8.1";
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

const settings: AppSettingsState = loadJson(settingsFile, DEFAULT_SETTINGS);
let nodes: NodeInfo[] = loadJson<NodeInfo[]>(nodesFile, []);
const pings = new Map<string, number>();

function saveSettings(): void {
  writeFileSync(settingsFile, JSON.stringify(settings, null, 2), "utf-8");
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
app.on("before-quit", () => {
  quitting = true;
});

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
function pickNode(requested: string | "best"): NodeInfo | null {
  if (requested !== "best") {
    return nodes.find((n) => n.id === requested) ?? null;
  }
  const ranked = rankByPing(nodes, pings);
  return ranked[0] ?? null;
}

async function doConnect(requested: string | "best"): Promise<void> {
  if (nodes.length === 0) {
    connection = { status: "error", message: "ابتدا لینک اشتراک را در تنظیمات وارد کنید" };
    broadcast();
    return;
  }
  const node = pickNode(requested);
  if (!node) {
    connection = { status: "error", message: "سروری برای اتصال پیدا نشد" };
    broadcast();
    return;
  }
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
    // Give the core a moment to bind, then verify the local inbound answers.
    await new Promise((r) => setTimeout(r, 600));
    if (!core.isRunning()) throw new Error("core_exited_early");

    if (settings.systemProxy) {
      try {
        await setSystemProxy(true);
      } catch {
        /* proxy is a convenience; the tunnel itself is the product */
      }
    }
    connectedSince = Date.now();
    const latency = pings.get(node.id) ?? 0;
    connection = {
      status: "connected",
      node: node.name,
      since: connectedSince,
      latencyMs: latency,
      localPort: 10808,
    };
  } catch (err) {
    connection = {
      status: "error",
      message: err instanceof Error ? err.message : "اتصال برقرار نشد",
    };
    await core.stop();
  }
  broadcast();
}

async function failover(deadNodeId: string, reason: string): Promise<void> {
  connection = { status: "reconnecting", node: activeNodeId ?? "", reason };
  broadcast();
  const next = rankByPing(nodes, pings).filter((n) => n.id !== deadNodeId)[0];
  if (!next) {
    connection = { status: "error", message: `${reason} — سرور جایگزینی نمانده` };
    broadcast();
    return;
  }
  if (reconnectTimer) clearTimeout(reconnectTimer);
  reconnectTimer = setTimeout(() => void doConnect(next.id), 1200);
}

async function doDisconnect(): Promise<void> {
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
  await core.stop();
  try {
    await setSystemProxy(false);
  } catch {
    /* best effort */
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
const PS_COUNTER = `Get-Counter '\\Network Interface(*)\\Bytes Total/sec' -ErrorAction SilentlyContinue | ForEach-Object { $_.CounterSamples | Measure-Object -Property CookedValue -Sum | Select-Object -ExpandProperty Sum }`;

function sampleSpeed(): void {
  const { spawn } = require("node:child_process") as typeof import("node:child_process");
  const child = spawn(
    "powershell.exe",
    ["-NoProfile", "-NonInteractive", "-Command", PS_COUNTER],
    { windowsHide: true, stdio: ["ignore", "pipe", "ignore"] },
  );
  let out = "";
  child.stdout.on("data", (d: Buffer) => (out += d.toString()));
  child.on("exit", () => {
    const value = Number(out.trim().replace(",", "."));
    if (Number.isFinite(value) && value >= 0) {
      const now = Date.now();
      if (lastBytes) {
        const dt = (now - lastBytes.at) / 1000;
        if (dt > 0) {
          speed.push({ t: now, down: value, up: 0 });
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
    if (!settings.systemProxy && connection.status === "connected") {
      try {
        await setSystemProxy(false);
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
