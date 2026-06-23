export const MANIFEST_FILE = "manifest.json";
export const BOOKMARKS_FILE = "bookmarks.json";
export const WEBAPPS_FILE = "webapps.json";
export const SYNC_ROOT = "HyperBrowserSync";
export const SYNC_FILES = ["bookmarks.json", "webapps.json", "launcher.json", "devices/"] as const;

export type SyncSettings = {
  webDavUrl: string;
  username: string;
  password: string;
  folderTitle: string;
  folderId: string;
  deviceName: string;
  deviceId: string;
};

export type SyncDocument<T> = {
  type: string;
  schemaVersion: number;
  updatedAt: number;
  items: T[];
};

export type BookmarkRecord = {
  url: string;
  title: string;
  createdAt: number;
  updatedAt: number;
  deletedAt: number | null;
  sourceDeviceId: string;
  iconDataUrl?: string | null;
};

export type WebAppRecord = {
  id: string;
  name: string;
  startUrl: string;
  scopeUrl: string;
  themeColor: number;
  displayMode: string;
  createdAt: number;
  lastOpenedAt: number;
  updatedAt: number;
  deletedAt: number | null;
  sourceDeviceId: string;
  iconDataUrl?: string | null;
  iconSource?: "custom" | "site" | "title";
};

export type SyncMetadata = {
  bookmarks: Record<string, BookmarkRecord>;
  webApps: Record<string, WebAppRecord>;
};

export type RemoteSyncManifest = {
  updatedAt: number;
  lastWriter: string;
};

export type SyncRecord = {
  updatedAt: number;
  deletedAt: number | null;
};

export function mergeBookmarkRecords(remoteRecords: BookmarkRecord[], localRecords: BookmarkRecord[]): Record<string, BookmarkRecord> {
  return mergeRecords(indexBookmarks(remoteRecords), indexBookmarks(localRecords));
}

export function mergeRecords<T extends SyncRecord>(remote: Record<string, T>, local: Record<string, T>): Record<string, T> {
  const result: Record<string, T> = {};
  new Set([...Object.keys(remote), ...Object.keys(local)]).forEach((key) => {
    const left = remote[key];
    const right = local[key];
    result[key] = chooseLatest(left, right);
  });
  return result;
}

export function chooseLatest<T extends SyncRecord>(left?: T, right?: T): T {
  if (!left && !right) throw new Error("Missing records.");
  if (!left) return right!;
  if (!right) return left;
  if (right.updatedAt > left.updatedAt) return right;
  if (left.updatedAt > right.updatedAt) return left;
  return right.deletedAt && !left.deletedAt ? right : left;
}

export function sameBookmarkRecords(left: BookmarkRecord[], right: BookmarkRecord[]): boolean {
  return recordListSignature(left, bookmarkRecordSignature) === recordListSignature(right, bookmarkRecordSignature);
}

export function bookmarksDocument(items: BookmarkRecord[]): SyncDocument<BookmarkRecord> {
  return {
    type: "hyper-browser-bookmarks",
    schemaVersion: 1,
    updatedAt: Date.now(),
    items: [...items].sort((a, b) => Number(!!a.deletedAt) - Number(!!b.deletedAt) || a.title.localeCompare(b.title)),
  };
}

export function webAppsDocument(items: WebAppRecord[]): SyncDocument<WebAppRecord> {
  return {
    type: "hyper-browser-webapps",
    schemaVersion: 1,
    updatedAt: Date.now(),
    items,
  };
}

export function indexBookmarks(items: BookmarkRecord[]): Record<string, BookmarkRecord> {
  return Object.fromEntries(items.filter((item) => item.url).map((item) => [item.url, item]));
}

export function indexWebApps(items: WebAppRecord[]): Record<string, WebAppRecord> {
  const records: Record<string, WebAppRecord> = {};
  items.forEach((item) => {
    const id = item.id?.trim();
    if (!id) return;
    const current = records[id];
    records[id] = current ? chooseLatestWebApp(current, item) : { ...item, id };
  });
  return records;
}

export function applyLocalWebAppTombstones(records: Record<string, WebAppRecord>, metadata: SyncMetadata): void {
  Object.values(metadata.webApps).forEach((record) => {
    if (!record.deletedAt) return;
    const current = records[record.id];
    if (!current || record.deletedAt >= current.updatedAt) {
      records[record.id] = record;
    }
  });
}

export function activeWebApps(records: Record<string, WebAppRecord>): WebAppRecord[] {
  return Object.values(records).filter((item) => !item.deletedAt);
}

export function mergeWebAppRecordsRemoteFirst(remoteRecords: WebAppRecord[], localRecords: WebAppRecord[]): WebAppRecord[] {
  const merged = indexWebApps(remoteRecords);
  const remoteStartUrls = new Set(
    Object.values(merged)
      .map((item) => item.startUrl.trim())
      .filter(Boolean),
  );
  localRecords.forEach((record) => {
    const startUrl = record.startUrl.trim();
    const remoteRecord = merged[record.id];
    if (!remoteRecord) {
      if (record.deletedAt || !remoteStartUrls.has(startUrl)) {
        merged[record.id] = record;
        if (startUrl) remoteStartUrls.add(startUrl);
      }
      return;
    }
    merged[record.id] = chooseRemotePrimaryWebApp(remoteRecord, record);
  });
  return Object.values(merged);
}

export function sameWebAppRecords(left: WebAppRecord[], right: WebAppRecord[]): boolean {
  return recordListSignature(Object.values(indexWebApps(left)), webAppRecordSignature) ===
    recordListSignature(Object.values(indexWebApps(right)), webAppRecordSignature);
}

export function latestWebAppForStartUrl(records: Record<string, WebAppRecord>, startUrl: string): WebAppRecord | undefined {
  return Object.values(records)
    .filter((item) => item.startUrl === startUrl)
    .sort((left, right) => right.updatedAt - left.updatedAt || Number(!!right.deletedAt) - Number(!!left.deletedAt))[0];
}

export function upsertWebAppRecord(
  records: Record<string, WebAppRecord>,
  input: Partial<WebAppRecord> & { name: string; startUrl: string },
  deviceId: string,
  now = Date.now(),
): WebAppRecord {
  const startUrl = input.startUrl.trim();
  if (!startUrl) throw new Error("Start URL is required.");
  const requestedId = input.id?.trim();
  const existingById = requestedId ? records[requestedId] : undefined;
  const existingByStartUrl = latestWebAppForStartUrl(records, startUrl);
  const existing = existingById || existingByStartUrl;
  const id = existing?.id || requestedId || crypto.randomUUID();
  const hasIconDataUrl = Object.prototype.hasOwnProperty.call(input, "iconDataUrl");
  const iconDataUrl = hasIconDataUrl ? input.iconDataUrl ?? null : existing?.iconDataUrl ?? null;
  const iconSource = input.iconSource || existing?.iconSource || (iconDataUrl ? "custom" : "title");
  const record: WebAppRecord = {
    id,
    name: input.name.trim() || startUrl,
    startUrl,
    scopeUrl: input.scopeUrl?.trim() || existing?.scopeUrl || scopeFor(startUrl),
    themeColor: input.themeColor ?? existing?.themeColor ?? 0xff126d6a,
    displayMode: input.displayMode || existing?.displayMode || "standalone",
    createdAt: existing?.createdAt || input.createdAt || now,
    lastOpenedAt: input.lastOpenedAt || existing?.lastOpenedAt || now,
    updatedAt: now,
    deletedAt: null,
    sourceDeviceId: deviceId,
    iconDataUrl,
    iconSource,
  };
  records[id] = record;
  return record;
}

export function tombstoneWebAppRecord(
  records: Record<string, WebAppRecord>,
  input: string | Partial<WebAppRecord> | null | undefined,
  deviceId: string,
  now = Date.now(),
): WebAppRecord | null {
  const key = typeof input === "string" ? input.trim() : (input?.id || "").trim();
  const fallback = typeof input === "string" || !input ? {} : input;
  const fallbackId = fallback.id?.trim();
  const existing = records[key] || (fallbackId ? records[fallbackId] : undefined);
  const id = existing?.id || fallbackId || key;
  const startUrl = existing?.startUrl || fallback.startUrl?.trim();
  if (!id || !startUrl) return null;
  const record: WebAppRecord = {
    id,
    name: existing?.name || fallback.name?.trim() || startUrl,
    startUrl,
    scopeUrl: existing?.scopeUrl || fallback.scopeUrl || scopeFor(startUrl),
    themeColor: existing?.themeColor ?? fallback.themeColor ?? 0xff126d6a,
    displayMode: existing?.displayMode || fallback.displayMode || "standalone",
    createdAt: existing?.createdAt || fallback.createdAt || now,
    lastOpenedAt: existing?.lastOpenedAt || fallback.lastOpenedAt || now,
    updatedAt: now,
    deletedAt: now,
    sourceDeviceId: deviceId,
    iconDataUrl: existing?.iconDataUrl ?? fallback.iconDataUrl ?? null,
    iconSource: existing?.iconSource || fallback.iconSource || "title",
  };
  records[id] = record;
  return record;
}

export function chooseLatestWebApp(left: WebAppRecord, right: WebAppRecord): WebAppRecord {
  if (right.updatedAt > left.updatedAt) return right;
  if (left.updatedAt > right.updatedAt) return left;
  return right.deletedAt && !left.deletedAt ? right : left;
}

export function chooseRemotePrimaryWebApp(remote: WebAppRecord, local: WebAppRecord): WebAppRecord {
  if (remote.deletedAt && !local.deletedAt) return remote;
  if (local.updatedAt > remote.updatedAt) return local;
  if (remote.updatedAt > local.updatedAt) return remote;
  if (local.deletedAt && !remote.deletedAt) return local;
  return remote;
}

export function normalizeTimestamp(value: unknown): number {
  return typeof value === "number" && Number.isFinite(value) && value > 0 ? value : 0;
}

export function scopeFor(url: string): string {
  try {
    const value = new URL(url);
    value.pathname = "/";
    value.search = "";
    value.hash = "";
    return value.toString();
  } catch {
    return url;
  }
}

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

function recordListSignature<T>(items: T[], signature: (item: T) => unknown): string {
  return JSON.stringify(items.map(signature).sort((left, right) => JSON.stringify(left).localeCompare(JSON.stringify(right))));
}

function bookmarkRecordSignature(item: BookmarkRecord) {
  return {
    url: item.url,
    title: item.title,
    createdAt: item.createdAt,
    updatedAt: item.updatedAt,
    deletedAt: item.deletedAt,
    sourceDeviceId: item.sourceDeviceId,
    iconDataUrl: item.iconDataUrl || null,
  };
}

function webAppRecordSignature(item: WebAppRecord) {
  return {
    id: item.id,
    name: item.name,
    startUrl: item.startUrl,
    scopeUrl: item.scopeUrl,
    themeColor: item.themeColor,
    displayMode: item.displayMode,
    createdAt: item.createdAt,
    lastOpenedAt: item.lastOpenedAt,
    updatedAt: item.updatedAt,
    deletedAt: item.deletedAt,
    sourceDeviceId: item.sourceDeviceId,
    iconDataUrl: item.iconDataUrl || null,
    iconSource: item.iconSource || null,
  };
}
