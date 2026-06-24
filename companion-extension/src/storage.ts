import { browser } from "wxt/browser";
import { DEFAULT_DEVICE_NAME } from "./identity";
import type { SyncSettings } from "./types";
import { createEmptyStore, ensureStore, type SyncV2Store } from "@hyper-sync/op-log";

const DEFAULT_FOLDER_TITLE = "Hyper Browser";

const DEFAULT_SETTINGS: SyncSettings = {
  webDavUrl: "",
  username: "",
  password: "",
  folderTitle: DEFAULT_FOLDER_TITLE,
  folderId: "",
  deviceName: DEFAULT_DEVICE_NAME,
  deviceId: "",
};

export function getDefaultSettings(): SyncSettings {
  return { ...DEFAULT_SETTINGS };
}

export async function loadSettings(): Promise<SyncSettings> {
  const result = await storageGet<{ settings?: Partial<SyncSettings> }>("settings");
  const settings = { ...DEFAULT_SETTINGS, ...(result.settings || {}) };
  if (!settings.deviceId) {
    settings.deviceId = crypto.randomUUID();
    await saveSettings(settings);
  }
  return settings;
}

export async function saveSettings(settings: SyncSettings): Promise<void> {
  await storageSet({ settings });
}

export async function loadSyncV2Store(): Promise<SyncV2Store> {
  const settings = await loadSettings();
  const result = await storageGet<{ syncV2Store?: unknown }>("syncV2Store");
  return result.syncV2Store ? ensureStore(result.syncV2Store, settings.deviceId) : createEmptyStore(settings.deviceId);
}

export async function saveSyncV2Store(store: SyncV2Store): Promise<void> {
  await storageSet({ syncV2Store: ensureStore(store, store.deviceId) });
}

export function storageGet<T>(keys: string | string[] | Record<string, unknown> | null): Promise<T> {
  return browser.storage.local.get(keys as never) as Promise<T>;
}

export function storageSet(items: Record<string, unknown>): Promise<void> {
  return browser.storage.local.set(items);
}
