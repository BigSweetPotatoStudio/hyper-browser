import type { LauncherLayout, LauncherLayoutStorage } from "./index";

const LAUNCHER_FILE = "launcher.json";
const MAX_SYNC_ATTEMPTS = 3;

export type LauncherLayoutSyncSettings = {
  webDavUrl: string;
  username: string;
  password: string;
  deviceId: string;
  deviceName: string;
  clientName: string;
};

type RemoteLauncherDocument = {
  type: "hyper-browser-launcher";
  schemaVersion: number;
  updatedAt: number;
  sourceDeviceId: string;
  sourceDeviceName: string;
  layout: LauncherLayout;
};

type RemoteJson<T> = {
  data: T;
  etag: string | null;
};

export type LauncherLayoutSyncResult = {
  changed: boolean;
  direction: "pull" | "push" | "none";
  updatedAt: number;
};

export async function syncLauncherLayout(
  storage: LauncherLayoutStorage,
  settings: LauncherLayoutSyncSettings,
): Promise<LauncherLayoutSyncResult> {
  const client = new LauncherWebDavClient(settings);
  await client.ensureCollections();

  let lastConflict: unknown;
  for (let attempt = 0; attempt < MAX_SYNC_ATTEMPTS; attempt += 1) {
    const local = completeLayout(await storage.load());
    const remote = await client.getJson<RemoteLauncherDocument>(LAUNCHER_FILE);
    const remoteLayout = completeLayout(remote?.data.layout);
    const localUpdatedAt = validTimestamp(local?.updatedAt);
    const remoteUpdatedAt = remoteLayout ? validTimestamp(remote?.data.updatedAt || remoteLayout.updatedAt) : 0;

    if (remoteLayout && remoteUpdatedAt > localUpdatedAt) {
      await storage.save({ ...remoteLayout, updatedAt: remoteUpdatedAt });
      return { changed: true, direction: "pull", updatedAt: remoteUpdatedAt };
    }

    if (!local) {
      return { changed: false, direction: "none", updatedAt: remoteUpdatedAt };
    }

    if (localUpdatedAt > remoteUpdatedAt || !remote) {
      const updatedAt = localUpdatedAt || Date.now();
      try {
        await client.putJson(LAUNCHER_FILE, launcherDocument(local, settings, updatedAt), remote?.etag);
        await client.putManifest();
        await client.putDeviceState();
        if (!local.updatedAt) await storage.save({ ...local, updatedAt });
        return { changed: true, direction: "push", updatedAt };
      } catch (error) {
        if (error instanceof WebDavConflictError) {
          lastConflict = error;
          continue;
        }
        throw error;
      }
    }

    await client.putDeviceState();
    return { changed: false, direction: "none", updatedAt: remoteUpdatedAt };
  }

  throw lastConflict instanceof Error ? lastConflict : new Error("Unable to sync launcher layout.");
}

function launcherDocument(layout: LauncherLayout, settings: LauncherLayoutSyncSettings, updatedAt: number): RemoteLauncherDocument {
  return {
    type: "hyper-browser-launcher",
    schemaVersion: 1,
    updatedAt,
    sourceDeviceId: settings.deviceId,
    sourceDeviceName: settings.deviceName,
    layout: { ...layout, updatedAt },
  };
}

function validTimestamp(value: unknown): number {
  return typeof value === "number" && Number.isFinite(value) && value > 0 ? value : 0;
}

function completeLayout(value: Awaited<ReturnType<LauncherLayoutStorage["load"]>>): LauncherLayout | null {
  if (!value || !Array.isArray(value.cells) || !Array.isArray(value.dock) || !Array.isArray(value.folders)) return null;
  return {
    version: 4,
    cells: value.cells,
    dock: value.dock,
    folders: value.folders,
    gridColumns: typeof value.gridColumns === "number" ? value.gridColumns : undefined,
    updatedAt: validTimestamp(value.updatedAt) || undefined,
  };
}

class LauncherWebDavClient {
  private readonly rootUrl: string;

  constructor(private readonly settings: LauncherLayoutSyncSettings) {
    this.rootUrl = normalizeRootUrl(settings.webDavUrl);
  }

  async ensureCollections(): Promise<void> {
    await this.mkcol("");
    await this.mkcol("devices/");
  }

  async getJson<T>(path: string): Promise<RemoteJson<T> | null> {
    const response = await fetch(this.urlFor(path), {
      method: "GET",
      headers: this.headers(),
    });
    if (response.status === 404) return null;
    if (!response.ok) throw new Error(`WebDAV GET failed: HTTP ${response.status}`);
    return {
      data: await response.json() as T,
      etag: response.headers.get("ETag"),
    };
  }

  async putJson(path: string, body: unknown, etag?: string | null): Promise<void> {
    const headers = this.headers({ "Content-Type": "application/json; charset=utf-8" });
    if (etag) headers.set("If-Match", etag);
    const response = await fetch(this.urlFor(path), {
      method: "PUT",
      headers,
      body: JSON.stringify(body, null, 2),
    });
    if (response.status === 409 || response.status === 412) throw new WebDavConflictError();
    if (!response.ok) throw new Error(`WebDAV PUT failed: HTTP ${response.status}`);
  }

  async putManifest(): Promise<void> {
    await this.putJson("manifest.json", {
      type: "hyper-browser-sync",
      schemaVersion: 1,
      updatedAt: Date.now(),
      syncRoot: "HyperBrowserSync",
      lastWriter: this.settings.deviceId,
      files: ["bookmarks.json", "webapps.json", "launcher.json", "devices/"],
    });
  }

  async putDeviceState(): Promise<void> {
    await this.putJson(`devices/${safeSegment(this.settings.clientName)}-${safeSegment(this.settings.deviceId)}.json`, {
      schemaVersion: 1,
      deviceId: this.settings.deviceId,
      deviceName: this.settings.deviceName,
      client: this.settings.clientName,
      lastSyncAt: Date.now(),
    });
  }

  private async mkcol(path: string): Promise<void> {
    const response = await fetch(this.urlFor(path), {
      method: "MKCOL",
      headers: this.headers(),
    });
    if (response.ok || response.status === 405) return;
    if (response.status === 409 && path === "devices/") {
      await this.mkcol("");
      return;
    }
    throw new Error(`WebDAV MKCOL failed: HTTP ${response.status}`);
  }

  private headers(extra?: Record<string, string>): Headers {
    const headers = new Headers(extra);
    if (this.settings.username || this.settings.password) {
      headers.set("Authorization", `Basic ${base64Utf8(`${this.settings.username}:${this.settings.password}`)}`);
    }
    return headers;
  }

  private urlFor(path: string): string {
    if (!path) return this.rootUrl;
    const encoded = path.split("/")
      .filter(Boolean)
      .map((segment) => encodeURIComponent(segment))
      .join("/");
    return `${this.rootUrl}${encoded}${path.endsWith("/") ? "/" : ""}`;
  }
}

class WebDavConflictError extends Error {
  constructor() {
    super("WebDAV write conflict.");
  }
}

function normalizeRootUrl(value: string): string {
  const clean = value.trim().replace(/\/+$/, "");
  if (!clean) throw new Error("WebDAV URL is required.");
  if (!/^https?:\/\//i.test(clean)) throw new Error("WebDAV URL must start with http:// or https://.");
  return clean.toLowerCase().endsWith("/hyperbrowsersync") ? `${clean}/` : `${clean}/HyperBrowserSync/`;
}

function base64Utf8(value: string): string {
  const bytes = new TextEncoder().encode(value);
  let binary = "";
  bytes.forEach((byte) => {
    binary += String.fromCharCode(byte);
  });
  return btoa(binary);
}

function safeSegment(value: string): string {
  return value.replace(/[^a-zA-Z0-9._-]/g, "_") || crypto.randomUUID();
}
