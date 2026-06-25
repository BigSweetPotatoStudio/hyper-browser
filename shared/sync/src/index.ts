export const SYNC_ROOT = "HyperBrowserSync";

export type SyncSettings = {
  webDavUrl: string;
  username: string;
  password: string;
  folderTitle: string;
  folderId: string;
  deviceName: string;
  deviceId: string;
};

export type BookmarkRecord = {
  url: string;
  title: string;
  createdAt: number;
  updatedAt: number;
  deletedAt: number | null;
  iconDataUrl?: string | null;
};

export type WebAppRecord = {
  id: string;
  name: string;
  startUrl: string;
  themeColor: number;
  displayMode: string;
  createdAt: number;
  lastOpenedAt: number;
  updatedAt: number;
  deletedAt: number | null;
  iconDataUrl?: string | null;
  iconSource?: "custom" | "site" | "title";
};

export function hostLabel(url: string): string {
  try {
    return new URL(url).hostname || url;
  } catch {
    return url;
  }
}

export function isHttpUrl(url: string): boolean {
  return /^https?:\/\//i.test(url);
}

export type SyncRefreshSignal = {
  changed?: boolean;
  launcherChanged?: boolean;
};

export type SyncStatusSignal = SyncRefreshSignal & {
  stateChanged?: boolean;
};

export function shouldRefreshLauncherAfterSync(result: SyncRefreshSignal): boolean {
  return result.launcherChanged ?? result.changed ?? false;
}

export function shouldUpdateSyncStatusAfterRemoteCheck(result: SyncStatusSignal): boolean {
  return result.stateChanged ?? result.changed ?? false;
}
