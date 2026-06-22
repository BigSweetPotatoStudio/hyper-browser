import type { SyncSettings } from "./types";

export type RemoteJson<T> = {
  data: T;
  etag: string | null;
};

export class WebDavClient {
  readonly rootUrl: string;

  constructor(private readonly settings: SyncSettings) {
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
    if (response.status === 409 || response.status === 412) {
      throw new WebDavConflictError();
    }
    if (!response.ok) throw new Error(`WebDAV PUT failed: HTTP ${response.status}`);
  }

  async putDeviceState(): Promise<void> {
    const now = Date.now();
    await this.putJson(`devices/chrome-${safeSegment(this.settings.deviceId)}.json`, {
      schemaVersion: 1,
      deviceId: this.settings.deviceId,
      deviceName: this.settings.deviceName || "Chrome",
      client: "hyper-browser-chrome-extension",
      lastSyncAt: now,
    });
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

export class WebDavConflictError extends Error {
  constructor() {
    super("WebDAV write conflict.");
  }
}

export function normalizeRootUrl(value: string): string {
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
