import type { LauncherLayout, LauncherLayoutStorage } from "@hyper-launcher";

const LAYOUT_STORAGE_KEY = "hyper-home-launcher-layout-v3";
const LEGACY_LAYOUT_STORAGE_KEY = "hyper-home-launcher-layout-v1";

export function createLauncherLayoutStorage(): LauncherLayoutStorage {
  return {
    async load() {
      const nativeLayout = await window.hyperBrowser.requestLauncherLayout().catch((error) => {
        console.warn("Unable to load native launcher layout.", error);
        return null;
      });
      if (nativeLayout) return nativeLayout as Partial<LauncherLayout>;

      const legacyLayout = readJson(LAYOUT_STORAGE_KEY) || readJson(LEGACY_LAYOUT_STORAGE_KEY);
      if (legacyLayout) {
        window.hyperBrowser.saveLauncherLayout(legacyLayout as LauncherLayout)
          .catch((error) => console.warn("Unable to migrate launcher layout.", error));
      }
      return legacyLayout as Partial<LauncherLayout> | null;
    },
    save(layout: LauncherLayout) {
      return window.hyperBrowser.saveLauncherLayout(layout);
    },
  };
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
