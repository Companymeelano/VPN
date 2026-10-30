/**
 * The IPC contract between Electron's main process and the renderer.
 * One file, types only — the renderer never imports Node APIs and the main process never
 * imports DOM types; this is the only shared surface.
 */

import type { NodeInfo } from "./nodes";
import type { RegimeId } from "./regime";
import type { UpdateCheck } from "./update";

export type ConnectionState =
  | { status: "idle" }
  | { status: "connecting"; node: string; step: string }
  | { status: "connected"; node: string; since: number; latencyMs: number; localPort: number }
  | { status: "reconnecting"; node: string; reason: string }
  | { status: "error"; message: string };

export interface AppSettingsState {
  theme: "dark" | "light" | "system";
  regime: RegimeId;
  systemProxy: boolean;
  autoStart: boolean;
  autoConnect: boolean;
  subscriptionUrl: string;
  lanAccess: boolean;
}

export interface SpeedPoint {
  /** epoch ms */
  t: number;
  /** bytes/sec (system-wide interface counter) */
  down: number;
}

export interface AppSnapshot {
  connection: ConnectionState;
  settings: AppSettingsState;
  nodes: NodeInfo[];
  pings: Record<string, number>;
  activeNodeId: string | null;
  speed: SpeedPoint[];
  appVersion: string;
  coreVersion: string;
}

/** Progress of the in-app installer download (bytes of the saved setup.exe). */
export interface UpdateProgress {
  percent: number;
  bytes: number;
  total: number;
}

export interface MeelanoApi {
  getSnapshot(): Promise<AppSnapshot>;
  connect(nodeId: string | "best"): Promise<void>;
  disconnect(): Promise<void>;
  refreshNodes(): Promise<number>;
  testPings(): Promise<Record<string, number>>;
  updateSettings(patch: Partial<AppSettingsState>): Promise<AppSettingsState>;
  openExternal(url: string): void;
  minimize(): void;
  close(): void;
  onSnapshot(cb: (snap: AppSnapshot) => void): () => void;
  /** null = already on the newest desktop release. */
  checkUpdate(): Promise<UpdateCheck | null>;
  /** Downloads the setup installer to the user's Downloads folder and verifies its sha256. */
  downloadUpdate(): Promise<string>;
  /** Runs the saved installer (upgrade in place) and quits the app. */
  installUpdate(): Promise<void>;
  /** Reveals the downloaded installer in Explorer. */
  openUpdateFile(): Promise<void>;
  onUpdateProgress(cb: (p: UpdateProgress) => void): () => void;
}
