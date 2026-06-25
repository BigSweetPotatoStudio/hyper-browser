import { browser } from "wxt/browser";
import { DEFAULT_DEVICE_NAME } from "./identity";
import type { SyncSettings } from "./types";
import { canonicalJson, createEmptyStore, ensureStore, type SyncV2Store } from "@hyper-sync/op-log";
import type { SyncJsonByFileName, SyncJsonFileName } from "@hyper-sync/sync-json-types";

const DEFAULT_FOLDER_TITLE = "Hyper Browser";
const LEGACY_LAUNCHER_LAYOUT_KEY = "launcherLayout";

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
  await removeLegacyLauncherLayout();
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
  if (!result.syncV2Store) return createEmptyStore(settings.deviceId);
  const store = ensureStore(result.syncV2Store, settings.deviceId);
  if (canonicalJson(result.syncV2Store) !== canonicalJson(store)) {
    await storageSet({ syncV2Store: store });
  }
  return store;
}

export async function saveSyncV2Store(store: SyncV2Store): Promise<void> {
  await storageSet({ syncV2Store: ensureStore(store, store.deviceId) });
}

export async function readSyncFile(path: SyncJsonFileName): Promise<SyncJsonByFileName[SyncJsonFileName] | null> {
  const result = await storageGet<Partial<Record<SyncJsonFileName, SyncJsonByFileName[SyncJsonFileName]>>>(path);
  return result[path] || null;
}

export async function saveSyncFile(path: SyncJsonFileName, data: SyncJsonByFileName[SyncJsonFileName]): Promise<void> {
  await storageSet({ [path]: data });
}

async function removeLegacyLauncherLayout(): Promise<void> {
  await storageRemove(LEGACY_LAUNCHER_LAYOUT_KEY).catch(() => undefined);
}

export function storageGet<T>(keys: string | string[] | Record<string, unknown> | null): Promise<T> {
  return browser.storage.local.get(keys as never) as Promise<T>;
}

export function storageSet(items: Record<string, unknown>): Promise<void> {
  return browser.storage.local.set(items);
}

export function storageRemove(keys: string | string[]): Promise<void> {
  return browser.storage.local.remove(keys);
}
