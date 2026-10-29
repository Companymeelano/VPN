/**
 * Xray core lifecycle: locate the bundled core, write a profile, run it, keep it honest.
 *
 * Health is deliberately *not* "the process is alive": a config error or a dead node keeps
 * xray.exe running while moving nothing. Two signals count — the process exited (fail fast),
 * and a tiny HTTP probe through the local inbound answers (proof the tunnel moves traffic).
 * Both are checked on the main thread's timer; the probe runs through the proxy itself, so it
 * also exercises the exact path the user's traffic will take.
 */

import { spawn, type ChildProcess } from "node:child_process";
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { buildConfig, type ProfileOptions } from "../shared/profile";
import type { NodeInfo } from "../shared/nodes";
import type { Tune } from "../shared/regime";

const HERE = dirname(fileURLToPath(import.meta.url));

/** resources/core sits next to dist/ in a packaged app; the repo layout during dev differs. */
export function coreBinary(): string | null {
  const candidates = [
    join(HERE, "..", "..", "resources", "core", "xray.exe"),
    join(process.resourcesPath ?? "", "core", "xray.exe"),
    join(HERE, "..", "..", "..", "resources", "core", "xray.exe"),
  ];
  for (const c of candidates) if (c && existsSync(c)) return c;
  return null;
}

export interface CoreHandle {
  stop(): Promise<void>;
  isRunning(): boolean;
  exitCode(): number | null;
}

export class XrayCore {
  private child: ChildProcess | null = null;
  private lastExit: number | null = null;

  constructor(private readonly profileDir: string) {
    mkdirSync(profileDir, { recursive: true });
  }

  isRunning(): boolean {
    return this.child !== null && this.child.exitCode === null && this.child.signalCode === null;
  }

  exitCode(): number | null {
    return this.lastExit;
  }

  async start(node: NodeInfo, tune: Tune, opts: ProfileOptions, onExit: (code: number | null) => void): Promise<CoreHandle> {
    await this.stop();
    const bin = coreBinary();
    if (!bin) throw new Error("core_missing");
    const config = buildConfig(node, tune, opts);
    const file = join(this.profileDir, "config.json");
    writeFileSync(file, JSON.stringify(config, null, 2), "utf-8");

    const child = spawn(bin, ["run", "-c", file], {
      windowsHide: true,
      stdio: "ignore",
    });
    this.child = child;
    this.lastExit = null;
    child.on("exit", (code) => {
      this.lastExit = code;
      if (this.child === child) this.child = null;
      onExit(code);
    });
    child.on("error", () => {
      this.lastExit = -1;
      if (this.child === child) this.child = null;
      onExit(-1);
    });
    return {
      stop: () => this.stop(),
      isRunning: () => this.isRunning(),
      exitCode: () => this.lastExit,
    };
  }

  async stop(): Promise<void> {
    const child = this.child;
    if (!child) return;
    this.child = null;
    await new Promise<void>((resolve) => {
      const done = () => resolve();
      child.once("exit", done);
      child.kill();
      setTimeout(() => {
        if (child.exitCode === null) {
          try {
            child.kill();
          } catch {
            /* already gone */
          }
        }
        resolve();
      }, 1500);
    });
  }

  /**
   * Synchronous last-resort kill for process-exit hooks: an Electron app that quits without
   * stopping the core leaves xray.exe running as an orphan (Windows does not cascade process
   * death), silently keeping a tunnel alive the user thinks they closed.
   */
  forceKill(): void {
    const child = this.child;
    this.child = null;
    if (child && child.exitCode === null) {
      try {
        child.kill();
      } catch {
        /* already gone */
      }
    }
  }
}

/**
 * TCP reachability probe with real timing: a connect that completes under [timeoutMs].
 * -1 = unreachable. This is the same measurement the Android node list shows as «پینگ».
 */
export function tcpPing(host: string, port: number, timeoutMs = 3000): Promise<number> {
  return new Promise((resolve) => {
    const net = require("node:net") as typeof import("node:net");
    const started = Date.now();
    const socket = net.createConnection({ host, port });
    const finish = (value: number) => {
      socket.removeAllListeners();
      socket.destroy();
      resolve(value);
    };
    socket.setTimeout(timeoutMs);
    socket.on("connect", () => finish(Date.now() - started));
    socket.on("timeout", () => finish(-1));
    socket.on("error", () => finish(-1));
  });
}

/** Fetch and return raw subscription text (base64 or URI list). Throws on HTTP failure. */
export async function fetchSubscription(url: string, timeoutMs = 15000): Promise<string> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(url, {
      signal: controller.signal,
      redirect: "follow",
      headers: { "User-Agent": "MA-VPN-Desktop/2.8.3" },
    });
    if (!res.ok) throw new Error(`http_${res.status}`);
    return await res.text();
  } finally {
    clearTimeout(timer);
  }
}
