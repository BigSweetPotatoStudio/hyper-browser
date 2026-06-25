import type { LauncherLayout, LauncherLayoutStorage } from "@hyper-launcher";
import { layoutFromState } from "@hyper-sync/op-log";
import { browser } from "wxt/browser";
import { loadSyncV2Store } from "./storage";

export const DEFAULT_DOCK_ENTRY_IDS = ["system:bookmarks", "system:history", "system:extensions"];
export const DEPRECATED_ENTRY_IDS = ["system:chrome"];

let launcherLayoutSaveQueue: Promise<void> = Promise.resolve();

export const launcherLayoutStorage: LauncherLayoutStorage = {
  async load() {
    return layoutFromState((await loadSyncV2Store()).state) as LauncherLayout;
  },
  save(layout: LauncherLayout, options) {
    const run = launcherLayoutSaveQueue.then(() => saveLauncherLayoutIfCurrent(layout, options?.reason || "user"));
    launcherLayoutSaveQueue = run.catch(() => undefined);
    return run;
  },
};

async function saveLauncherLayoutIfCurrent(layout: LauncherLayout, reason: "user" | "system"): Promise<void> {
  if (reason !== "user") return;
  const response = await browser.runtime.sendMessage({ type: "launcher.layout.save", payload: layout });
  if (!response?.ok) throw new Error(response?.error || "Unable to save launcher layout.");
}
