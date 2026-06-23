import {
  applyLocalWebAppTombstones,
  BOOKMARKS_FILE,
  bookmarksDocument,
  indexBookmarks,
  indexWebApps,
  mergeBookmarkRecords,
  mergeWebAppRecordsRemoteFirst,
  sameBookmarkRecords,
  sameWebAppRecords,
  WEBAPPS_FILE,
  webAppsDocument,
  MANIFEST_FILE,
  normalizeTimestamp,
  type BookmarkRecord,
  type RemoteSyncManifest,
  type SyncDocument,
  type SyncMetadata,
  type WebAppRecord,
} from "@hyper-sync";
import { WebDavClient, WebDavConflictError } from "@hyper-sync/webdav";
import type { BrowserSettings, WebDavLocalSyncData, WebDavSyncResult } from "./hyper-browser";

const MAX_SYNC_ATTEMPTS = 3;
const ANDROID_CLIENT_NAME = "hyper-browser-android";
const ANDROID_DEVICE_STATE_PREFIX = "android";

let lastSeenRemoteManifestUpdatedAt = 0;

export async function runAndroidWebDavSync(baseSettings?: BrowserSettings): Promise<WebDavSyncResult> {
  const initialSettings = baseSettings || await window.hyperBrowser.requestSettingsData();
  if (!initialSettings.webDavSyncUrl.trim()) {
    throw new Error("WebDAV URL is required.");
  }
  const localData = await window.hyperBrowser.requestWebDavLocalData();
  const settings = localData.settings || initialSettings;
  const client = new WebDavClient(webDavSettings(settings));
  await client.ensureCollections();

  let lastConflict: unknown;
  for (let attempt = 1; attempt <= MAX_SYNC_ATTEMPTS; attempt += 1) {
    const remoteBookmarks = await client.getJson<SyncDocument<BookmarkRecord>>(BOOKMARKS_FILE);
    const remoteWebApps = await client.getJson<SyncDocument<WebAppRecord>>(WEBAPPS_FILE);
    const metadata = normalizeMetadata(localData);
    const localBookmarkRecords = localData.bookmarks || [];
    const mergedBookmarks = mergeBookmarkRecords(remoteBookmarks?.data.items || [], localBookmarkRecords);
    const mergedBookmarkItems = Object.values(mergedBookmarks);

    const remoteWebAppRecords = indexWebApps(remoteWebApps?.data.items || []);
    applyLocalWebAppTombstones(remoteWebAppRecords, metadata);
    const localWebAppRecords = Object.values(indexWebApps(localData.webApps || []));
    const mergedWebAppItems = mergeWebAppRecordsRemoteFirst(Object.values(remoteWebAppRecords), localWebAppRecords);

    const bookmarksChanged = !sameBookmarkRecords(remoteBookmarks?.data.items || [], mergedBookmarkItems);
    const webAppsChanged = !sameWebAppRecords(remoteWebApps?.data.items || [], mergedWebAppItems);

    try {
      if (bookmarksChanged) {
        await client.putJson(BOOKMARKS_FILE, bookmarksDocument(mergedBookmarkItems), remoteBookmarks?.etag);
      }
      if (webAppsChanged) {
        await client.putJson(WEBAPPS_FILE, webAppsDocument(mergedWebAppItems), remoteWebApps?.etag);
      }
      if (bookmarksChanged || webAppsChanged) {
        await client.putManifest(settings.webDavSyncDeviceId || localData.deviceId);
      }
      await client.putDeviceState({
        deviceId: settings.webDavSyncDeviceId || localData.deviceId,
        deviceName: settings.webDavSyncDeviceName || localData.deviceName || "Hyper Browser Android",
        clientName: ANDROID_CLIENT_NAME,
        deviceStatePrefix: ANDROID_DEVICE_STATE_PREFIX,
      });
      const result = await window.hyperBrowser.applyWebDavSyncRecords({
        bookmarks: mergedBookmarkItems,
        webApps: mergedWebAppItems,
      });
      return { ...result, attemptCount: attempt };
    } catch (error) {
      if (error instanceof WebDavConflictError) {
        lastConflict = error;
        continue;
      }
      throw error;
    }
  }

  throw lastConflict instanceof Error ? lastConflict : new Error("WebDAV sync failed.");
}

export async function runAndroidWebDavSyncIfEnabled(): Promise<WebDavSyncResult | null> {
  const settings = await window.hyperBrowser.requestSettingsData();
  if (!settings.webDavSyncEnabled || !settings.webDavSyncUrl.trim()) return null;
  return runAndroidWebDavSync(settings);
}

export async function checkAndroidWebDavRemoteChanges(pageLastSeenRemoteManifestUpdatedAt = 0): Promise<{
  changed: boolean;
  synced: boolean;
  updatedAt: number;
  syncResult?: WebDavSyncResult;
}> {
  const settings = await window.hyperBrowser.requestSettingsData();
  if (!settings.webDavSyncEnabled || !settings.webDavSyncUrl.trim()) {
    return { changed: false, synced: false, updatedAt: 0 };
  }
  const manifest = await readRemoteSyncManifest(settings);
  if (!manifest) return { changed: false, synced: false, updatedAt: 0 };
  const deviceId = settings.webDavSyncDeviceId;
  const pageNeedsRefresh = manifest.updatedAt > Math.max(0, pageLastSeenRemoteManifestUpdatedAt || 0) &&
    manifest.lastWriter !== deviceId;

  if (manifest.lastWriter === deviceId) {
    lastSeenRemoteManifestUpdatedAt = manifest.updatedAt;
    return { changed: false, synced: false, updatedAt: manifest.updatedAt };
  }
  if (manifest.updatedAt <= lastSeenRemoteManifestUpdatedAt) {
    return { changed: pageNeedsRefresh, synced: false, updatedAt: manifest.updatedAt };
  }

  const syncResult = await runAndroidWebDavSync(settings);
  lastSeenRemoteManifestUpdatedAt = manifest.updatedAt;
  return {
    changed: true,
    synced: true,
    updatedAt: manifest.updatedAt,
    syncResult,
  };
}

function normalizeMetadata(localData: WebDavLocalSyncData): SyncMetadata {
  return {
    bookmarks: indexBookmarks(localData.metadata?.bookmarks || []),
    webApps: indexWebApps(localData.metadata?.webApps || []),
  };
}

function webDavSettings(settings: BrowserSettings) {
  return {
    webDavUrl: settings.webDavSyncUrl,
    username: settings.webDavSyncUsername,
    password: settings.webDavSyncPassword,
  };
}

async function readRemoteSyncManifest(settings: BrowserSettings): Promise<RemoteSyncManifest | null> {
  const client = new WebDavClient(webDavSettings(settings));
  const remote = await client.getJson<Partial<RemoteSyncManifest>>(MANIFEST_FILE);
  if (!remote) return null;
  const updatedAt = normalizeTimestamp(remote.data.updatedAt);
  if (!updatedAt) return null;
  return {
    updatedAt,
    lastWriter: typeof remote.data.lastWriter === "string" ? remote.data.lastWriter : "",
  };
}
