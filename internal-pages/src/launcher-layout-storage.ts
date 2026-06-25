import type { LauncherLayout, LauncherLayoutStorage } from "@hyper-launcher";
import { sendBackgroundCommand } from "./background-command";

const LAYOUT_STORAGE_KEY = "hyper-home-launcher-layout-v3";
const LEGACY_LAYOUT_STORAGE_KEY = "hyper-home-launcher-layout-v1";

let launcherLayoutSaveQueue: Promise<void> = Promise.resolve();

export function createLauncherLayoutStorage(): LauncherLayoutStorage {
  return {
    async load() {
      await waitForLauncherLayoutSaves();
      const nativeLayout = await window.hyperBrowser.requestLauncherLayout().catch((error) => {
        console.warn("Unable to load native launcher layout.", error);
        return null;
      });
      if (nativeLayout) return nativeLayout as Partial<LauncherLayout>;

      const legacyLayout = readJson(LAYOUT_STORAGE_KEY) || readJson(LEGACY_LAYOUT_STORAGE_KEY);
      if (legacyLayout) {
        sendBackgroundCommand("launcher.layout.save", legacyLayout)
          .catch((error) => console.warn("Unable to migrate launcher layout.", error));
      }
      return legacyLayout as Partial<LauncherLayout> | null;
    },
    save(layout: LauncherLayout, options) {
      const run = launcherLayoutSaveQueue.then(async () => {
        if ((options?.reason || "user") !== "user") return;
        await sendBackgroundCommand("launcher.layout.save", layout);
      });
      launcherLayoutSaveQueue = run.catch(() => undefined);
      return run;
    },
  };
}

export function waitForLauncherLayoutSaves(): Promise<void> {
  return launcherLayoutSaveQueue;
}

function readJson(key: string): Record<string, unknown> | null {
  const raw = window.localStorage.getItem(key);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as Record<string, unknown>;
  } catch {
    return null;
  }
}
