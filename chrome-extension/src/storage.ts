import type { SyncMetadata, SyncSettings } from "./types";

const DEFAULT_FOLDER_TITLE = "Hyper Browser";

const DEFAULT_SETTINGS: SyncSettings = {
  webDavUrl: "",
  username: "",
  password: "",
  folderTitle: DEFAULT_FOLDER_TITLE,
  folderId: "",
  deviceName: "Chrome",
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

export async function loadMetadata(): Promise<SyncMetadata> {
  const result = await storageGet<{ metadata?: SyncMetadata }>("metadata");
  return result.metadata || { bookmarks: {} };
}

export async function saveMetadata(metadata: SyncMetadata): Promise<void> {
  await storageSet({ metadata });
}

export function storageGet<T>(keys: string | string[] | Record<string, unknown> | null): Promise<T> {
  return new Promise((resolve) => chrome.storage.local.get(keys, (items) => resolve(items as T)));
}

export function storageSet(items: Record<string, unknown>): Promise<void> {
  return new Promise((resolve) => chrome.storage.local.set(items, () => resolve()));
}
