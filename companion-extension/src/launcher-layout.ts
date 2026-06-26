import type { LauncherLayoutStorage } from "@hyper-launcher";
import type { LauncherJson } from "@hyper-sync/sync-json-types";
import { browser } from "wxt/browser";
import { readSyncFile } from "./storage";

export const DEPRECATED_ENTRY_IDS = ["system:chrome"];

let launcherLayoutSaveQueue: Promise<void> = Promise.resolve();

export const launcherLayoutStorage: LauncherLayoutStorage = {
  async load() {
    const layout = await readSyncFile("launcher.json");
    if (isLauncherJson(layout)) return layout;
    return null;
  },
  save(layout: LauncherJson, options) {
    const run = launcherLayoutSaveQueue.then(() => saveLauncherLayoutIfCurrent(layout, options?.reason || "user"));
    launcherLayoutSaveQueue = run.catch(() => undefined);
    return run;
  },
};

async function saveLauncherLayoutIfCurrent(layout: LauncherJson, reason: "user" | "system"): Promise<void> {
  if (reason !== "user") return;
  const response = await browser.runtime.sendMessage({ type: "launcher.layout.save", payload: layout });
  if (!response?.ok) throw new Error(response?.error || "Unable to save launcher layout.");
}

function isLauncherJson(value: unknown): value is LauncherJson {
  return !!value && typeof value === "object" && !Array.isArray(value) && "rev" in value;
}
