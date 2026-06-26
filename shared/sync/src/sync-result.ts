export type SyncResultLike = {
  bookmarkCount: number;
  webAppCount: number;
  deletedBookmarkCount: number;
  deletedWebAppCount: number;
  pendingOperationCount: number;
};

export type SyncResultFormatLabels = {
  complete: (counts: { bookmarks: number; webApps: number; deleted: number }) => string;
  pending?: (pending: number) => string;
};

export function isSyncResultLike(value: unknown): value is SyncResultLike {
  if (!value || typeof value !== "object") return false;
  const candidate = value as Partial<SyncResultLike>;
  return typeof candidate.bookmarkCount === "number" &&
    typeof candidate.webAppCount === "number" &&
    typeof candidate.deletedBookmarkCount === "number" &&
    typeof candidate.deletedWebAppCount === "number" &&
    typeof candidate.pendingOperationCount === "number";
}

export function formatSyncResult(
  result: SyncResultLike,
  labels: SyncResultFormatLabels = defaultSyncResultLabels,
): string {
  const deleted = result.deletedBookmarkCount + result.deletedWebAppCount;
  const summary = labels.complete({
    bookmarks: result.bookmarkCount,
    webApps: result.webAppCount,
    deleted,
  });
  if (result.pendingOperationCount <= 0) return summary;
  return `${summary}${labels.pending?.(result.pendingOperationCount) || ""}`;
}

const defaultSyncResultLabels: SyncResultFormatLabels = {
  complete: ({ bookmarks, webApps, deleted }) => {
    const tombstones = deleted > 0 ? `, ${deleted} tombstones` : "";
    return `Synced ${bookmarks} bookmarks and ${webApps} WebApps${tombstones}`;
  },
  pending: (pending) => `, ${pending} pending changes`,
};
