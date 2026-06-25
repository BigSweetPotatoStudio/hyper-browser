import assert from "node:assert/strict";
import test from "node:test";
import {
  createEmptyState,
  mergeSyncStates,
} from "../src/op-log";
import type {
  BookmarkSyncRecord,
  LauncherJson,
  SyncRevision,
  SyncTombstone,
  SyncV2State,
  WebAppSyncRecord,
} from "../src/sync-json-types";

const bookmarkUrl = "https://example.com/";

test("bookmark merge keeps the newest edited title", () => {
  const older = bookmarkRecord({ title: "Old title", updatedAt: 1_000, deviceId: "phone" });
  const newer = bookmarkRecord({ title: "New title", updatedAt: 2_000, deviceId: "desktop" });

  const merged = mergeSyncStates(
    state({ bookmarks: { [bookmarkUrl]: older } }),
    state({ bookmarks: { [bookmarkUrl]: newer } }),
  );

  assert.equal(merged.bookmarks[bookmarkUrl].title, "New title");
  assert.deepEqual(merged.bookmarks[bookmarkUrl].rev, revision(2_000, "desktop"));
});

test("webapp tombstone prevents an older device from resurrecting a deleted app", () => {
  const app = webAppRecord({ id: "app-1", name: "Old app", updatedAt: 1_000, deviceId: "phone" });
  const tombstone: SyncTombstone = {
    deletedAt: "2026-06-26T00:00:00.000Z",
    rev: revision(2_000, "desktop"),
  };

  const merged = mergeSyncStates(
    state({ apps: { "app-1": app } }),
    state({ appTombstones: { "app-1": tombstone } }),
  );

  assert.equal(merged.apps["app-1"], undefined);
  assert.deepEqual(merged.appTombstones["app-1"].rev, revision(2_000, "desktop"));
});

test("older launcher layout does not overwrite the newer layout revision", () => {
  const app = webAppRecord({ id: "app-1", name: "App", updatedAt: 2_000, deviceId: "phone" });
  const newerLayout: LauncherJson = {
    pages: [{ cells: [{ id: "app:app-1", index: 0 }] }],
    rev: revision(3_000, "phone"),
  };
  const olderLayout: LauncherJson = {
    pages: [{ cells: [{ id: "system:bookmarks", index: 0 }] }],
    rev: revision(1_000, "desktop"),
  };

  const merged = mergeSyncStates(
    state({ apps: { "app-1": app }, layout: newerLayout }),
    state({ apps: { "app-1": app }, layout: olderLayout }),
  );

  assert.deepEqual(merged.layout.pages?.[0]?.cells, [{ id: "app:app-1", index: 0 }]);
  assert.deepEqual(merged.layout.rev, revision(3_000, "phone"));
});

test("partial webapp and launcher files converge when merged together", () => {
  const app = webAppRecord({ id: "app-1", name: "App", updatedAt: 2_000, deviceId: "phone" });
  const layoutOnly: LauncherJson = {
    pages: [{ cells: [{ id: "app:app-1", index: 0 }] }],
    rev: revision(2_000, "desktop"),
  };

  const merged = mergeSyncStates(
    state({ apps: { "app-1": app } }),
    state({ layout: layoutOnly }),
  );

  assert.equal(merged.apps["app-1"].name, "App");
  assert.deepEqual(merged.layout.pages?.[0]?.cells, [{ id: "app:app-1", index: 0 }]);
});

test("legacy rev.counter is read as rev.updatedAt and not written back", () => {
  const legacyBookmark = {
    ...bookmarkRecord({ title: "Legacy", updatedAt: 0, deviceId: "phone" }),
    rev: { counter: 4_000, deviceId: "phone" },
  } as unknown as BookmarkSyncRecord;

  const merged = mergeSyncStates(
    state({ bookmarks: { [bookmarkUrl]: legacyBookmark } }),
    state(),
  );

  assert.equal(merged.bookmarks[bookmarkUrl].rev.updatedAt, 4_000);
  assert.equal("counter" in merged.bookmarks[bookmarkUrl].rev, false);
});

function state(partial: Partial<SyncV2State> = {}): SyncV2State {
  const empty = createEmptyState();
  return {
    ...empty,
    ...partial,
    bookmarks: partial.bookmarks || empty.bookmarks,
    bookmarkTombstones: partial.bookmarkTombstones || empty.bookmarkTombstones,
    apps: partial.apps || empty.apps,
    appTombstones: partial.appTombstones || empty.appTombstones,
    layout: partial.layout || empty.layout,
  };
}

function revision(updatedAt: number, deviceId: string): SyncRevision {
  return { updatedAt, deviceId };
}

function bookmarkRecord(input: { title: string; updatedAt: number; deviceId: string }): BookmarkSyncRecord {
  return {
    url: bookmarkUrl,
    title: input.title,
    createdAt: 900,
    updatedAt: input.updatedAt,
    rev: revision(input.updatedAt, input.deviceId),
  };
}

function webAppRecord(input: { id: string; name: string; updatedAt: number; deviceId: string }): WebAppSyncRecord {
  return {
    id: input.id,
    name: input.name,
    startUrl: `https://${input.id}.example/`,
    themeColor: 0xff126d6a,
    displayMode: "standalone",
    createdAt: 900,
    lastOpenedAt: 900,
    updatedAt: input.updatedAt,
    iconDataUrl: null,
    iconSource: "title",
    rev: revision(input.updatedAt, input.deviceId),
  };
}
