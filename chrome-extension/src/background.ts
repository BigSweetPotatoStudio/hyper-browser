import { appendWebAppToLauncher, syncLauncherLayoutNow } from "./launcher-layout";
import { loadSettings } from "./storage";
import { addBookmarkToSyncFolder, deleteRemoteWebApp, loadRemoteWebApps, saveRemoteWebApp, syncNow } from "./sync";

const MAX_CAPTURED_ICON_BYTES = 1024 * 1024;
const CAPTURED_ICON_SIZE = 128;

chrome.runtime.onInstalled.addListener(() => {
  loadSettings().catch(console.error);
});

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (!message || typeof message.type !== "string") return false;
  handleMessage(message)
    .then((data) => sendResponse({ ok: true, data }))
    .catch((error) => sendResponse({ ok: false, error: error instanceof Error ? error.message : String(error) }));
  return true;
});

async function handleMessage(message: { type: string; payload?: unknown }): Promise<unknown> {
  switch (message.type) {
    case "settings.get":
      return loadSettings();
    case "sync.run":
      return syncNow();
    case "current.addWebApp": {
      const page = await getCurrentHttpPage();
      const iconDataUrl = await capturePageIcon(await getPageIconCandidates(page)).catch(() => null);
      const webApps = await saveRemoteWebApp({
        name: page.title,
        startUrl: page.url,
        ...(iconDataUrl ? { iconDataUrl, iconSource: "site" as const } : {}),
      });
      const savedApp = webApps.find((app) => app.startUrl === page.url);
      if (savedApp) {
        await syncLauncherLayoutNow();
        await appendWebAppToLauncher(savedApp.id, webApps.map((app) => app.id));
        await syncLauncherLayoutNow();
      }
      return webApps;
    }
    case "current.addBookmark": {
      const page = await getCurrentHttpPage();
      return addBookmarkToSyncFolder(page);
    }
    case "webapps.list":
      return loadRemoteWebApps();
    case "webapps.save":
      return saveRemoteWebApp(message.payload as never);
    case "webapps.delete":
      return deleteRemoteWebApp(String((message.payload as { startUrl?: string })?.startUrl || ""));
    case "open.options":
      await chrome.runtime.openOptionsPage();
      return null;
    case "open.home":
      await chrome.tabs.create({ url: chrome.runtime.getURL("home.html") });
      return null;
    case "open.webapps":
      await chrome.tabs.create({ url: chrome.runtime.getURL("webapps.html") });
      return null;
    default:
      throw new Error("Unknown command.");
  }
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
  return new Promise((resolve) => {
    chrome.scripting.executeScript({
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
    }, (results) => {
      const error = chrome.runtime.lastError;
      if (error) {
        resolve([]);
        return;
      }
      const value = results?.[0]?.result;
      resolve(Array.isArray(value) ? value.filter((item): item is string => typeof item === "string" && item.length > 0) : []);
    });
  });
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

function getActiveTab(): Promise<chrome.tabs.Tab> {
  return new Promise((resolve, reject) => {
    chrome.tabs.query({ active: true, lastFocusedWindow: true }, (tabs) => {
      const error = chrome.runtime.lastError;
      if (error) {
        reject(new Error(error.message));
        return;
      }
      const tab = tabs[0];
      if (!tab) {
        reject(new Error("No active tab."));
        return;
      }
      resolve(tab);
    });
  });
}

function uniqueStrings(values: unknown[]): string[] {
  const seen = new Set<string>();
  return values.filter((value): value is string => {
    if (typeof value !== "string" || !value || seen.has(value)) return false;
    seen.add(value);
    return true;
  });
}
