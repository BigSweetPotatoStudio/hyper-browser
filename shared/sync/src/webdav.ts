import { SYNC_ROOT } from "./index";

export type WebDavSettings = {
  webDavUrl: string;
  username: string;
  password: string;
};

export type RemoteJson<T> = {
  data: T;
  etag: string | null;
};

export type WebDavEntry = {
  href: string;
  path: string;
  name: string;
  isCollection: boolean;
};

export class WebDavClient {
  readonly rootUrl: string;

  constructor(private readonly settings: WebDavSettings) {
    this.rootUrl = normalizeRootUrl(settings.webDavUrl);
  }

  async ensureCollections(): Promise<void> {
    await this.ensureCollectionExists("");
  }

  async ensureCollection(path: string): Promise<void> {
    await this.ensureCollectionExists(path.endsWith("/") ? path : `${path}/`);
  }

  async getJson<T>(path: string, options: { requireStrongEtag?: boolean } = {}): Promise<RemoteJson<T> | null> {
    const response = await fetch(this.urlFor(path), {
      method: "GET",
      headers: this.headers(noCacheHeaders()),
      cache: "no-store",
    });
    if (response.status === 404) return null;
    if (!response.ok) throw new Error(`WebDAV GET failed: HTTP ${response.status}`);
    const etag = response.headers.get("ETag");
    if (options.requireStrongEtag && (!etag || etag.startsWith("W/"))) {
      throw new Error("WebDAV server must provide a strong ETag.");
    }
    return {
      data: await response.json() as T,
      etag,
    };
  }

  async putJson(
    path: string,
    body: unknown,
    etag?: string | null,
    options: { ifNoneMatch?: boolean; bodyText?: string } = {},
  ): Promise<void> {
    const bodyText = options.bodyText ?? JSON.stringify(body, null, 2);
    const response = await this.putJsonRequest(path, bodyText, etag, options.ifNoneMatch === true);
    if (response.status === 405 && options.ifNoneMatch) {
      const remote = await this.getJson<unknown>(path);
      if (remote) throw new WebDavConflictError(412);
      throw new Error("WebDAV server does not support atomic create with If-None-Match.");
    }
    await this.handlePutResponse(response);
  }

  private putJsonRequest(
    path: string,
    bodyText: string,
    etag?: string | null,
    ifNoneMatch = false,
  ): Promise<Response> {
    const headers = this.headers({ "Content-Type": "application/json; charset=utf-8" });
    if (ifNoneMatch) {
      headers.set("If-None-Match", "*");
    } else if (etag) {
      headers.set("If-Match", etag);
    }
    return fetch(this.urlFor(path), {
      method: "PUT",
      headers,
      cache: "no-store",
      body: bodyText,
    });
  }

  private async handlePutResponse(response: Response): Promise<void> {
    if (response.status === 409 || response.status === 412) {
      throw new WebDavConflictError(response.status);
    }
    if (!response.ok) throw new Error(`WebDAV PUT failed: HTTP ${response.status}`);
  }

  async list(path = ""): Promise<WebDavEntry[]> {
    const response = await fetch(this.urlFor(path), {
      method: "PROPFIND",
      headers: this.headers({ ...noCacheHeaders(), Depth: "1" }),
      cache: "no-store",
      body: "<?xml version=\"1.0\" encoding=\"utf-8\" ?><propfind xmlns=\"DAV:\"><prop><resourcetype /></prop></propfind>",
    });
    if (response.status === 404) return [];
    if (!response.ok && response.status !== 207) throw new Error(`WebDAV PROPFIND failed: HTTP ${response.status}`);
    return parsePropfind(await response.text(), this.rootUrl, path);
  }

  private async ensureCollectionExists(path: string): Promise<void> {
    const before = await this.collectionExists(path);
    if (before === true) return;
    const mkcolStatus = await this.mkcol(path);
    if (before === false) {
      const after = await this.collectionExists(path);
      if (after !== true) {
        throw new Error(`WebDAV collection was not created: ${this.urlFor(path)} (MKCOL HTTP ${mkcolStatus})`);
      }
    }
  }

  private async collectionExists(path: string): Promise<boolean | null> {
    const response = await fetch(this.urlFor(path), {
      method: "PROPFIND",
      headers: this.headers({ ...noCacheHeaders(), Depth: "0" }),
      cache: "no-store",
      body: "<?xml version=\"1.0\" encoding=\"utf-8\" ?><propfind xmlns=\"DAV:\"><prop><resourcetype /></prop></propfind>",
    });
    if (response.status === 404) return false;
    if (response.status === 200 || response.status === 207) return true;
    if (!response.ok) return null;
    return true;
  }

  private async mkcol(path: string): Promise<number> {
    const response = await fetch(this.urlFor(path), {
      method: "MKCOL",
      headers: this.headers(noCacheHeaders()),
      cache: "no-store",
    });
    if (response.ok || response.status === 405) return response.status;
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

function noCacheHeaders(): Record<string, string> {
  return {
    "Cache-Control": "no-cache",
    Pragma: "no-cache",
  };
}

export class WebDavConflictError extends Error {
  readonly status: number;

  constructor(status = 412) {
    super("WebDAV write conflict.");
    this.status = status;
  }
}

export function normalizeRootUrl(value: string): string {
  const clean = value.trim().replace(/\/+$/, "");
  if (!clean) throw new Error("WebDAV URL is required.");
  if (!/^https?:\/\//i.test(clean)) throw new Error("WebDAV URL must start with http:// or https://.");
  return clean.toLowerCase().endsWith(`/${SYNC_ROOT.toLowerCase()}`) ? `${clean}/` : `${clean}/${SYNC_ROOT}/`;
}

function base64Utf8(value: string): string {
  const bytes = new TextEncoder().encode(value);
  let binary = "";
  bytes.forEach((byte) => {
    binary += String.fromCharCode(byte);
  });
  return btoa(binary);
}

function parsePropfind(xml: string, rootUrl: string, requestedPath: string): WebDavEntry[] {
  const root = new URL(rootUrl);
  const rootPath = ensureTrailingSlash(decodeURIComponent(root.pathname));
  const requested = normalizeDavPath(requestedPath);
  const responseMatches = [...xml.matchAll(/<[^:>]*(?::)?response\b[\s\S]*?<\/[^:>]*(?::)?response>/gi)];
  return responseMatches
    .map((match) => {
      const block = match[0];
      const hrefMatch = block.match(/<[^:>]*(?::)?href\b[^>]*>([\s\S]*?)<\/[^:>]*(?::)?href>/i);
      if (!hrefMatch) return null;
      const href = decodeXml(hrefMatch[1].trim());
      const hrefUrl = new URL(href, rootUrl);
      const decodedPath = ensureLeadingSlash(decodeURIComponent(hrefUrl.pathname));
      const relativePath = normalizeDavPath(decodedPath.startsWith(rootPath)
        ? decodedPath.slice(rootPath.length)
        : decodedPath.replace(/^\/+/, ""));
      if (relativePath === requested) return null;
      const isCollection = /<[^:>]*(?::)?collection\s*\/?>/i.test(block) || hrefUrl.pathname.endsWith("/");
      const cleanPath = isCollection ? ensureTrailingSlash(relativePath) : relativePath;
      const name = cleanPath.split("/").filter(Boolean).at(-1) || "";
      if (!name) return null;
      return {
        href,
        path: cleanPath,
        name,
        isCollection,
      };
    })
    .filter((entry): entry is WebDavEntry => !!entry);
}

function normalizeDavPath(path: string): string {
  return path.replace(/^\/+/, "").replace(/\/+$/, "");
}

function ensureTrailingSlash(value: string): string {
  return value.endsWith("/") ? value : `${value}/`;
}

function ensureLeadingSlash(value: string): string {
  return value.startsWith("/") ? value : `/${value}`;
}

function decodeXml(value: string): string {
  return value
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, "\"")
    .replace(/&#39;/g, "'");
}
