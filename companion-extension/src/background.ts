import { browser, type Browser } from "wxt/browser";
import { createSyncBackgroundController, type SyncBackgroundRunOptions } from "@hyper-sync/background";
import { createBackgroundCommandHandler } from "@hyper-sync/background-adapter";
import type { BrowserBookmarkEvent } from "./companion-bookmarks";
import { companionSync } from "./companion-sync";
import { loadSettings } from "./storage";
import type { SyncResult } from "./types";

const MAX_CAPTURED_ICON_BYTES = 1024 * 1024;
const CAPTURED_ICON_SIZE = 128;
const AUTO_SYNC_DEBOUNCE_MS = 1800;
const REMOTE_SYNC_ALARM = "hyper-browser-remote-sync";
const REMOTE_SYNC_ALARM_MINUTES = 1;
let chromeBookmarkSnapshotTimer: ReturnType<typeof setTimeout> | null = null;
let pendingChromeBookmarkEvents: BrowserBookmarkEvent[] = [];

const syncBackground = createSyncBackgroundController({
  debounceMs: AUTO_SYNC_DEBOUNCE_MS,
  syncNow: runBookmarkSyncNow,
  syncIfEnabled: syncIfConfigured,
  notifyLauncherChanged,
  notifySyncResult,
  onError: (_scope, error) => console.error(error),
});

const backgroundAdapter = companionSync.createBackgroundAdapter({
  sync: syncBackground,
  getSettings: loadSettings,
  getCurrentPage: getCurrentPageInfo,
  notifyLauncherChanged,
});

const hyperCommands = createBackgroundCommandHandler(backgroundAdapter);

export function startBackground(): void {
  browser.runtime.onInstalled.addListener(() => {
    loadSettings().catch(console.error);
    ensureRemoteSyncAlarm();
  });

  browser.runtime.onStartup.addListener(() => {
    ensureRemoteSyncAlarm();
    syncBackground.checkRemoteChanges({ notifyPages: true }).catch(console.error);
  });

  browser.alarms.onAlarm.addListener((alarm) => {
    if (alarm.name === REMOTE_SYNC_ALARM) {
      syncBackground.checkRemoteChanges({ notifyPages: true }).catch(console.error);
    }
  });

  browser.bookmarks.onCreated.addListener((id, node) => scheduleChromeBookmarkSnapshot({
    id,
    parentId: node.parentId,
  }));
  browser.bookmarks.onChanged.addListener((id) => scheduleChromeBookmarkSnapshot({ id }));
  browser.bookmarks.onMoved.addListener((id, moveInfo) => scheduleChromeBookmarkSnapshot({
    id,
    parentId: moveInfo.parentId,
    oldParentId: moveInfo.oldParentId,
  }));
  browser.bookmarks.onRemoved.addListener((id, removeInfo) => scheduleChromeBookmarkSnapshot({
    id,
    parentId: removeInfo.parentId,
  }));

  browser.runtime.onMessage.addListener((message, _sender, sendResponse) => {
    if (!message || typeof message.type !== "string") return false;
    handleMessage(message)
      .then((data) => sendResponse({ ok: true, data }))
      .catch((error) => sendResponse({ ok: false, error: error instanceof Error ? error.message : String(error) }));
    return true;
  });
}

async function handleMessage(message: { type: string; payload?: unknown }): Promise<unknown> {
  const shared = await hyperCommands.handle(message);
  if (shared.handled) return shared.data;

  switch (message.type) {
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

async function getCurrentPageInfo() {
  const page = await getCurrentHttpPage();
  const iconDataUrl = await capturePageIcon(page.tabId, await getPageIconCandidates(page)).catch(() => null);
  return {
    title: page.title,
    url: page.url,
    iconDataUrl,
  };
}

function ensureRemoteSyncAlarm() {
  browser.alarms.create(REMOTE_SYNC_ALARM, {
    delayInMinutes: REMOTE_SYNC_ALARM_MINUTES,
    periodInMinutes: REMOTE_SYNC_ALARM_MINUTES,
  });
}

async function runBookmarkSyncNow(options?: SyncBackgroundRunOptions) {
  return companionSync.syncNow(options);
}

async function syncIfConfigured(): Promise<SyncResult | null> {
  const settings = await loadSettings();
  if (!settings.webDavUrl.trim()) return null;
  return runBookmarkSyncNow();
}

function scheduleChromeBookmarkSnapshot(event?: BrowserBookmarkEvent): void {
  if (event) pendingChromeBookmarkEvents.push(event);
  if (chromeBookmarkSnapshotTimer) clearTimeout(chromeBookmarkSnapshotTimer);
  chromeBookmarkSnapshotTimer = setTimeout(() => {
    chromeBookmarkSnapshotTimer = null;
    flushChromeBookmarkSnapshot().catch(console.error);
  }, AUTO_SYNC_DEBOUNCE_MS);
}

async function flushChromeBookmarkSnapshot(): Promise<void> {
  const events = pendingChromeBookmarkEvents;
  pendingChromeBookmarkEvents = [];
  const changed = await companionSync.recordLocalBookmarkFolderSnapshot(events);
  if (changed === null) {
    pendingChromeBookmarkEvents.push(...events);
    scheduleChromeBookmarkSnapshot();
    return;
  }
  if (changed) syncBackground.scheduleSync();
}

function notifySyncResult(updatedAt: number, syncResult: SyncResult): void {
  browser.runtime.sendMessage({ type: "remote.synced", updatedAt, syncResult }).catch(() => undefined);
}

function notifyLauncherChanged(syncResult?: SyncResult): void {
  browser.runtime.sendMessage({
    type: "launcher.changed",
    updatedAt: Date.now(),
    ...(syncResult ? { syncResult } : {}),
  }).catch(() => undefined);
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

async function capturePageIcon(tabId: number, candidates: string[]): Promise<string | null> {
  for (const candidate of candidates) {
    const icon = await captureIconCandidate(tabId, candidate).catch(() => null);
    if (icon) return icon;
  }
  return null;
}

async function captureIconCandidate(tabId: number, iconUrl: string): Promise<string | null> {
  if (iconUrl.startsWith("data:image/")) {
    return normalizeIconDataUrl(tabId, iconUrl);
  }
  if (!/^https?:\/\//i.test(iconUrl)) return null;
  const response = await fetch(iconUrl, { cache: "force-cache" });
  if (!response.ok) return null;
  const blob = await response.blob();
  if (blob.size > MAX_CAPTURED_ICON_BYTES) return null;
  if (blob.type && !blob.type.toLowerCase().startsWith("image/")) return null;
  return normalizeIconBlob(tabId, blob);
}

async function normalizeIconDataUrl(tabId: number, dataUrl: string): Promise<string | null> {
  const response = await fetch(dataUrl);
  const blob = await response.blob();
  if (!blob.type.toLowerCase().startsWith("image/") || blob.size > MAX_CAPTURED_ICON_BYTES) return null;
  return normalizeIconBlob(tabId, blob);
}

async function normalizeIconBlob(tabId: number, blob: Blob): Promise<string | null> {
  const bitmapIcon = await normalizeBitmapIconBlob(blob);
  if (bitmapIcon) return bitmapIcon;
  if (!blob.type.toLowerCase().includes("svg")) return null;
  return normalizeSvgIconBlob(tabId, blob);
}

async function normalizeBitmapIconBlob(blob: Blob): Promise<string | null> {
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

async function normalizeSvgIconBlob(tabId: number, blob: Blob): Promise<string | null> {
  const text = await blob.text().catch(() => "");
  if (!/<svg[\s>]/i.test(text)) return null;
  const svgDataUrl = await blobToDataUrl(new Blob([text], { type: "image/svg+xml" }));
  return rasterizeIconInPage(tabId, svgDataUrl);
}

async function rasterizeIconInPage(tabId: number, imageUrl: string): Promise<string | null> {
  const results = await browser.scripting.executeScript({
    target: { tabId },
    args: [imageUrl, CAPTURED_ICON_SIZE],
    func: (src: string, size: number) => new Promise<string | null>((resolve) => {
      const image = new Image();
      const timeout = window.setTimeout(() => {
        image.onload = null;
        image.onerror = null;
        resolve(null);
      }, 5000);
      image.onload = () => {
        window.clearTimeout(timeout);
        const sourceSize = Math.max(image.naturalWidth, image.naturalHeight);
        if (sourceSize <= 0) {
          resolve(null);
          return;
        }
        const canvas = document.createElement("canvas");
        canvas.width = size;
        canvas.height = size;
        const context = canvas.getContext("2d");
        if (!context) {
          resolve(null);
          return;
        }
        context.clearRect(0, 0, size, size);
        const scale = size / sourceSize;
        const width = Math.max(1, Math.round(image.naturalWidth * scale));
        const height = Math.max(1, Math.round(image.naturalHeight * scale));
        const left = Math.round((size - width) / 2);
        const top = Math.round((size - height) / 2);
        context.drawImage(image, left, top, width, height);
        resolve(canvas.toDataURL("image/png"));
      };
      image.onerror = () => {
        window.clearTimeout(timeout);
        resolve(null);
      };
      image.src = src;
    }),
  }).catch(() => []);
  const value = results?.[0]?.result;
  return typeof value === "string" && value.startsWith("data:image/png;base64,") ? value : null;
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
