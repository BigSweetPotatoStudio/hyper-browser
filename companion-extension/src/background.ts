import { browser, type Browser } from "wxt/browser";
import { appendWebAppToLauncher, syncLauncherLayoutNow } from "./launcher-layout";
import { loadRemoteSyncState, loadSettings, saveRemoteSyncState } from "./storage";
import { addBookmarkToSyncFolder, deleteRemoteWebApp, loadRemoteWebApps, readRemoteSyncManifest, saveRemoteWebApp, syncNow } from "./sync";

const MAX_CAPTURED_ICON_BYTES = 1024 * 1024;
const CAPTURED_ICON_SIZE = 128;
const AUTO_SYNC_DEBOUNCE_MS = 1800;
const REMOTE_SYNC_ALARM = "hyper-browser-remote-sync";
const REMOTE_SYNC_ALARM_MINUTES = 1;

let launcherSyncTimer: ReturnType<typeof setTimeout> | null = null;
let launcherSyncRunning = false;
let launcherSyncPending = false;
let bookmarkSyncTimer: ReturnType<typeof setTimeout> | null = null;
let bookmarkSyncRunning = false;
let bookmarkSyncPending = false;
let bookmarkEventMuteDepth = 0;
let remoteCheckRunning = false;

export function startBackground(): void {
  browser.runtime.onInstalled.addListener(() => {
    loadSettings().catch(console.error);
    ensureRemoteSyncAlarm();
  });

  browser.runtime.onStartup.addListener(() => {
    ensureRemoteSyncAlarm();
    checkRemoteChanges({ notifyPages: true }).catch(console.error);
  });

  browser.alarms.onAlarm.addListener((alarm) => {
    if (alarm.name === REMOTE_SYNC_ALARM) {
      checkRemoteChanges({ notifyPages: true }).catch(console.error);
    }
  });

  browser.bookmarks.onCreated.addListener(() => scheduleBookmarkAutoSync());
  browser.bookmarks.onChanged.addListener(() => scheduleBookmarkAutoSync());
  browser.bookmarks.onMoved.addListener(() => scheduleBookmarkAutoSync());
  browser.bookmarks.onRemoved.addListener(() => scheduleBookmarkAutoSync());

  browser.runtime.onMessage.addListener((message, _sender, sendResponse) => {
    if (!message || typeof message.type !== "string") return false;
    handleMessage(message)
      .then((data) => sendResponse({ ok: true, data }))
      .catch((error) => sendResponse({ ok: false, error: error instanceof Error ? error.message : String(error) }));
    return true;
  });
}

async function handleMessage(message: { type: string; payload?: unknown }): Promise<unknown> {
  switch (message.type) {
    case "settings.get":
      return loadSettings();
    case "sync.run":
      return runBookmarkSyncNow();
    case "launcher.syncSoon":
      scheduleLauncherAutoSync();
      return null;
    case "remote.check":
      return checkRemoteChanges({ notifyPages: false });
    case "current.addWebApp": {
      const page = await getCurrentHttpPage();
      const iconDataUrl = await capturePageIcon(await getPageIconCandidates(page)).catch(() => null);
      const webAppId = crypto.randomUUID();
      const webApps = await saveRemoteWebApp({
        id: webAppId,
        name: page.title,
        startUrl: page.url,
        ...(iconDataUrl ? { iconDataUrl, iconSource: "site" as const } : {}),
      });
      const savedApp = webApps.find((app) => app.id === webAppId);
      if (savedApp) {
        await syncLauncherLayoutNow(webApps.map((app) => app.id));
        await appendWebAppToLauncher(savedApp.id, webApps.map((app) => app.id));
        await syncLauncherLayoutNow(webApps.map((app) => app.id));
      }
      return webApps;
    }
    case "current.addBookmark": {
      const page = await getCurrentHttpPage();
      return withBookmarkEventsMuted(() => addBookmarkToSyncFolder(page));
    }
    case "webapps.list":
      return loadRemoteWebApps();
    case "webapps.save":
      return saveRemoteWebApp(message.payload as never);
    case "webapps.delete":
      return deleteRemoteWebApp(String((message.payload as { id?: string; startUrl?: string })?.id || (message.payload as { startUrl?: string })?.startUrl || ""));
    case "open.options":
      await browser.runtime.openOptionsPage();
      return null;
    case "open.home":
      await browser.tabs.create({ url: browser.runtime.getURL("/home.html") });
      return null;
    case "open.webapps":
      await browser.tabs.create({ url: browser.runtime.getURL("/webapps.html") });
      return null;
    default:
      throw new Error("Unknown command.");
  }
}

function ensureRemoteSyncAlarm() {
  browser.alarms.create(REMOTE_SYNC_ALARM, {
    delayInMinutes: REMOTE_SYNC_ALARM_MINUTES,
    periodInMinutes: REMOTE_SYNC_ALARM_MINUTES,
  });
}

function scheduleLauncherAutoSync() {
  if (launcherSyncTimer) clearTimeout(launcherSyncTimer);
  launcherSyncTimer = setTimeout(() => {
    launcherSyncTimer = null;
    runLauncherAutoSync().catch(console.error);
  }, AUTO_SYNC_DEBOUNCE_MS);
}

async function runLauncherAutoSync(): Promise<void> {
  if (launcherSyncRunning) {
    launcherSyncPending = true;
    return;
  }
  launcherSyncRunning = true;
  try {
    do {
      launcherSyncPending = false;
      const settings = await loadSettings();
      if (!settings.webDavUrl.trim()) return;
      const webApps = await loadRemoteWebApps();
      await syncLauncherLayoutNow(webApps.map((app) => app.id));
    } while (launcherSyncPending);
  } finally {
    launcherSyncRunning = false;
  }
}

function scheduleBookmarkAutoSync() {
  if (bookmarkEventMuteDepth > 0) return;
  if (bookmarkSyncTimer) clearTimeout(bookmarkSyncTimer);
  bookmarkSyncTimer = setTimeout(() => {
    bookmarkSyncTimer = null;
    runBookmarkAutoSync().catch(console.error);
  }, AUTO_SYNC_DEBOUNCE_MS);
}

async function runBookmarkAutoSync(): Promise<void> {
  if (bookmarkSyncRunning) {
    bookmarkSyncPending = true;
    return;
  }
  bookmarkSyncRunning = true;
  try {
    do {
      bookmarkSyncPending = false;
      const settings = await loadSettings();
      if (!settings.webDavUrl.trim()) return;
      await withBookmarkEventsMuted(() => syncNow());
    } while (bookmarkSyncPending);
  } finally {
    bookmarkSyncRunning = false;
  }
}

async function runBookmarkSyncNow() {
  if (bookmarkSyncTimer) {
    clearTimeout(bookmarkSyncTimer);
    bookmarkSyncTimer = null;
  }
  return withBookmarkEventsMuted(() => syncNow());
}

async function withBookmarkEventsMuted<T>(operation: () => Promise<T>): Promise<T> {
  bookmarkEventMuteDepth += 1;
  try {
    return await operation();
  } finally {
    bookmarkEventMuteDepth = Math.max(0, bookmarkEventMuteDepth - 1);
  }
}

async function checkRemoteChanges(options: { notifyPages: boolean } = { notifyPages: false }): Promise<{ changed: boolean; synced: boolean; updatedAt: number }> {
  if (remoteCheckRunning) return { changed: false, synced: false, updatedAt: 0 };
  remoteCheckRunning = true;
  try {
    const settings = await loadSettings();
    if (!settings.webDavUrl.trim()) return { changed: false, synced: false, updatedAt: 0 };
    const manifest = await readRemoteSyncManifest(settings);
    if (!manifest) return { changed: false, synced: false, updatedAt: 0 };
    const state = await loadRemoteSyncState();
    if (manifest.updatedAt <= state.manifestUpdatedAt) {
      return { changed: false, synced: false, updatedAt: manifest.updatedAt };
    }
    if (manifest.lastWriter === settings.deviceId) {
      await saveRemoteSyncState({ manifestUpdatedAt: manifest.updatedAt });
      return { changed: false, synced: false, updatedAt: manifest.updatedAt };
    }

    await runBookmarkSyncNow();
    const webApps = await loadRemoteWebApps();
    await syncLauncherLayoutNow(webApps.map((app) => app.id));
    await saveRemoteSyncState({ manifestUpdatedAt: manifest.updatedAt });
    if (options.notifyPages) notifyRemoteSynced(manifest.updatedAt);
    return { changed: true, synced: true, updatedAt: manifest.updatedAt };
  } finally {
    remoteCheckRunning = false;
  }
}

function notifyRemoteSynced(updatedAt: number): void {
  browser.runtime.sendMessage({ type: "remote.synced", updatedAt }).catch(() => undefined);
}

type CurrentHttpPage = {
  tabId: number;
  title: string;
  url: string;
  favIconUrl?: string;
};

async function getCurrentHttpPage(): Promise<CurrentHttpPage> {
  const tab = await getActiveTab();
  const url = typeof tab.url === "string" ? tab.url.trim() : "";
  if (!/^https?:\/\//i.test(url)) throw new Error("Current tab must be an http:// or https:// page.");
  if (typeof tab.id !== "number") throw new Error("Current tab is not available.");
  return {
    tabId: tab.id,
    title: tab.title?.trim() || new URL(url).hostname || url,
    url,
    favIconUrl: typeof tab.favIconUrl === "string" ? tab.favIconUrl : undefined,
  };
}

async function getPageIconCandidates(page: CurrentHttpPage): Promise<string[]> {
  const pageIconUrls = await readPageIconUrls(page.tabId).catch(() => []);
  const fallbackIconUrl = new URL("/favicon.ico", page.url).toString();
  return uniqueStrings([
    ...pageIconUrls,
    page.favIconUrl,
    fallbackIconUrl,
  ]).filter((url) => url.startsWith("data:image/") || /^https?:\/\//i.test(url));
}

async function readPageIconUrls(tabId: number): Promise<string[]> {
  const results = await browser.scripting.executeScript({
    target: { tabId },
    func: () => {
      type IconCandidate = {
        href: string;
        rel: string;
        type: string;
        sizes: string;
      };
      const candidates: IconCandidate[] = Array.from(document.querySelectorAll<HTMLLinkElement>("link[rel]"))
        .map((link) => ({
          href: link.href,
          rel: link.rel.toLowerCase(),
          type: link.type.toLowerCase(),
          sizes: link.sizes?.value || "",
        }))
        .filter((link) => {
          const tokens = link.rel.split(/\s+/);
          return !!link.href && (tokens.includes("icon") || link.rel.includes("apple-touch-icon") || link.rel.includes("mask-icon"));
        });
      const score = (candidate: IconCandidate) => {
        let value = 50;
        if (candidate.rel.includes("apple-touch-icon")) value -= 20;
        if (candidate.type.includes("png")) value -= 12;
        if (candidate.type.includes("webp")) value -= 10;
        if (candidate.type.includes("jpeg") || candidate.type.includes("jpg")) value -= 8;
        if (candidate.type.includes("svg")) value += 10;
        const sizes = candidate.sizes.match(/\d+/g)?.map(Number).filter((size) => Number.isFinite(size)) || [];
        const largestSize = sizes.length > 0 ? Math.max(...sizes) : 0;
        if (largestSize >= 128) value -= 6;
        if (largestSize >= 192) value -= 6;
        return value;
      };
      return candidates
        .sort((left, right) => score(left) - score(right))
        .map((candidate) => candidate.href);
    },
  }).catch(() => []);
  const value = results?.[0]?.result;
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string" && item.length > 0) : [];
}

async function capturePageIcon(candidates: string[]): Promise<string | null> {
  for (const candidate of candidates) {
    const icon = await captureIconCandidate(candidate).catch(() => null);
    if (icon) return icon;
  }
  return null;
}

async function captureIconCandidate(iconUrl: string): Promise<string | null> {
  if (iconUrl.startsWith("data:image/")) {
    return normalizeIconDataUrl(iconUrl);
  }
  if (!/^https?:\/\//i.test(iconUrl)) return null;
  const response = await fetch(iconUrl, { cache: "force-cache" });
  if (!response.ok) return null;
  const blob = await response.blob();
  if (blob.size > MAX_CAPTURED_ICON_BYTES) return null;
  if (blob.type && !blob.type.toLowerCase().startsWith("image/")) return null;
  return normalizeIconBlob(blob);
}

async function normalizeIconDataUrl(dataUrl: string): Promise<string | null> {
  const response = await fetch(dataUrl);
  const blob = await response.blob();
  if (!blob.type.toLowerCase().startsWith("image/") || blob.size > MAX_CAPTURED_ICON_BYTES) return null;
  return normalizeIconBlob(blob);
}

async function normalizeIconBlob(blob: Blob): Promise<string | null> {
  const bitmap = await createImageBitmap(blob).catch(() => null);
  if (!bitmap) return null;
  const sourceSize = Math.max(bitmap.width, bitmap.height);
  if (sourceSize <= 0) {
    bitmap.close();
    return null;
  }
  const canvas = new OffscreenCanvas(CAPTURED_ICON_SIZE, CAPTURED_ICON_SIZE);
  const context = canvas.getContext("2d");
  if (!context) {
    bitmap.close();
    return null;
  }
  context.clearRect(0, 0, CAPTURED_ICON_SIZE, CAPTURED_ICON_SIZE);
  const scale = CAPTURED_ICON_SIZE / sourceSize;
  const width = Math.max(1, Math.round(bitmap.width * scale));
  const height = Math.max(1, Math.round(bitmap.height * scale));
  const left = Math.round((CAPTURED_ICON_SIZE - width) / 2);
  const top = Math.round((CAPTURED_ICON_SIZE - height) / 2);
  context.drawImage(bitmap, left, top, width, height);
  bitmap.close();
  const pngBlob = await canvas.convertToBlob({ type: "image/png" });
  return blobToDataUrl(pngBlob);
}

async function blobToDataUrl(blob: Blob): Promise<string> {
  const bytes = new Uint8Array(await blob.arrayBuffer());
  let binary = "";
  const chunkSize = 0x8000;
  for (let offset = 0; offset < bytes.length; offset += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + chunkSize));
  }
  return `data:${blob.type || "image/png"};base64,${btoa(binary)}`;
}

async function getActiveTab(): Promise<Browser.tabs.Tab> {
  const tabs = await browser.tabs.query({ active: true, lastFocusedWindow: true });
  const tab = tabs[0];
  if (!tab) throw new Error("No active tab.");
  return tab;
}

function uniqueStrings(values: unknown[]): string[] {
  const seen = new Set<string>();
  return values.filter((value): value is string => {
    if (typeof value !== "string" || !value || seen.has(value)) return false;
    seen.add(value);
    return true;
  });
}
