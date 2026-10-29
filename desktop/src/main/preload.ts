import { contextBridge, ipcRenderer } from "electron";
import type { AppSnapshot, AppSettingsState, MeelanoApi } from "../shared/contract";

/**
 * The only bridge. contextIsolation is on and nodeIntegration is off — the renderer sees
 * exactly this object and nothing else from the system.
 */
const api: MeelanoApi = {
  getSnapshot: () => ipcRenderer.invoke("app:snapshot") as Promise<AppSnapshot>,
  connect: (nodeId) => ipcRenderer.invoke("app:connect", nodeId) as Promise<void>,
  disconnect: () => ipcRenderer.invoke("app:disconnect") as Promise<void>,
  refreshNodes: () => ipcRenderer.invoke("app:refreshNodes") as Promise<number>,
  testPings: () => ipcRenderer.invoke("app:testPings") as Promise<Record<string, number>>,
  updateSettings: (patch) => ipcRenderer.invoke("app:settings", patch) as Promise<AppSettingsState>,
  openExternal: (url) => void ipcRenderer.send("app:openExternal", url),
  minimize: () => void ipcRenderer.send("window:minimize"),
  close: () => void ipcRenderer.send("window:close"),
  onSnapshot: (cb) => {
    const handler = (_e: Electron.IpcRendererEvent, snap: AppSnapshot) => cb(snap);
    ipcRenderer.on("app:snapshotChanged", handler);
    return () => ipcRenderer.removeListener("app:snapshotChanged", handler);
  },
};

contextBridge.exposeInMainWorld("meelano", api);
