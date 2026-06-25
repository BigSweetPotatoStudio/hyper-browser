import type { LauncherLayoutStorage } from "@hyper-launcher";
import type { LauncherJson } from "@hyper-sync/sync-json-types";
import { sendBackgroundCommand } from "./background-command";

let launcherLayoutSaveQueue: Promise<void> = Promise.resolve();

export function createLauncherLayoutStorage(): LauncherLayoutStorage {
  return {
    async load() {
      await waitForLauncherLayoutSaves();
      const nativeLayout = await window.hyperBrowser.requestLauncherLayout().catch((error) => {
        console.warn("Unable to load native launcher layout.", error);
        return null;
      });
      return nativeLayout as LauncherJson | null;
    },
    save(layout: LauncherJson, options) {
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
