import assert from "node:assert/strict";
import test from "node:test";
import {
  createEmptyState,
  mergeSyncStates,
  stateToSyncJsonFiles,
  syncV2,
  type SyncV2Store,
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

test("pullRemote sync applies remote launcher without uploading local state", async () => {
  const localApp = webAppRecord({ id: "local-app", name: "Local", updatedAt: 5_000, deviceId: "phone" });
  const remoteApp = webAppRecord({ id: "remote-app", name: "Remote", updatedAt: 1_000, deviceId: "desktop" });
  const localState = state({
    apps: { "local-app": localApp },
    layout: {
      pages: [{ cells: [{ id: "app:local-app", index: 0 }] }],
      rev: revision(5_000, "phone"),
    },
  });
  const remoteState = state({
    apps: { "remote-app": remoteApp },
    layout: {
      pages: [{ cells: [{ id: "app:remote-app", index: 0 }] }],
      rev: revision(1_000, "desktop"),
    },
  });
  const server = new FakeWebDavServer(stateToSyncJsonFiles(remoteState));

  await withFakeFetch(server, async () => {
    const sync = await runFakeSync(localState, { mode: "pullRemote" });

    assert.deepEqual(sync.savedState.layout.pages?.[0]?.cells, [{ id: "app:remote-app", index: 0 }]);
    assert.equal(sync.savedState.apps["local-app"], undefined);
    assert.equal(sync.savedState.apps["remote-app"].name, "Remote");
    assert.equal(server.putPaths.length, 0);
  });
});

test("pushLocal sync uploads local launcher instead of merging the remote layout", async () => {
  const localApp = webAppRecord({ id: "local-app", name: "Local", updatedAt: 1_000, deviceId: "phone" });
  const remoteApp = webAppRecord({ id: "remote-app", name: "Remote", updatedAt: 5_000, deviceId: "desktop" });
  const localState = state({
    apps: { "local-app": localApp },
    layout: {
      pages: [{ cells: [{ id: "app:local-app", index: 0 }] }],
      rev: revision(1_000, "phone"),
    },
  });
  const remoteState = state({
    apps: { "remote-app": remoteApp },
    layout: {
      pages: [{ cells: [{ id: "app:remote-app", index: 0 }] }],
      rev: revision(5_000, "desktop"),
    },
  });
  const server = new FakeWebDavServer(stateToSyncJsonFiles(remoteState));

  await withFakeFetch(server, async () => {
    const sync = await runFakeSync(localState, { mode: "pushLocal" });
    const remoteLauncher = server.file("launcher.json") as LauncherJson;

    assert.deepEqual(sync.savedState.layout.pages?.[0]?.cells, [{ id: "app:local-app", index: 0 }]);
    assert.deepEqual(remoteLauncher.pages?.[0]?.cells, [{ id: "app:local-app", index: 0 }]);
    assert.equal(sync.savedState.apps["remote-app"], undefined);
    assert.ok(server.putPaths.includes("launcher.json"));
  });
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

async function runFakeSync(
  initialState: SyncV2State,
  options: { mode?: "merge" | "pullRemote" | "pushLocal" } = {},
): Promise<{ savedState: SyncV2State; store: SyncV2Store }> {
  let savedState = initialState;
  let store: SyncV2Store = {
    schemaVersion: 2,
    deviceId: "phone",
    counter: 0,
  };
  await syncV2({
    settings: {
      webDavUrl: "https://example.com/dav",
      username: "",
      password: "",
      deviceId: "phone",
    },
    loadStore: async () => store,
    saveStore: async (next) => {
      store = next;
    },
    loadState: async () => savedState,
    saveState: async (next) => {
      savedState = next;
    },
    withLocalLock: async (operation) => operation(),
    mode: options.mode,
  });
  return { savedState, store };
}

async function withFakeFetch<T>(server: FakeWebDavServer, operation: () => Promise<T>): Promise<T> {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = server.fetch as typeof fetch;
  try {
    return await operation();
  } finally {
    globalThis.fetch = originalFetch;
  }
}

class FakeWebDavServer {
  readonly putPaths: string[] = [];
  private readonly files = new Map<string, { data: unknown; etag: string }>();
  private etagCounter = 0;

  constructor(files: Record<string, unknown>) {
    Object.entries(files).forEach(([path, data]) => {
      this.files.set(path, { data, etag: this.nextEtag() });
    });
  }

  file(path: string): unknown {
    return this.files.get(path)?.data;
  }

  fetch = async (input: Parameters<typeof fetch>[0], init?: Parameters<typeof fetch>[1]): Promise<Response> => {
    const method = String(init?.method || "GET").toUpperCase();
    const path = this.pathFromInput(input);
    if (method === "MKCOL") return new Response("", { status: 405 });
    if (method === "GET") {
      const file = this.files.get(path);
      if (!file) return new Response("", { status: 404 });
      return new Response(JSON.stringify(file.data), {
        status: 200,
        headers: { ETag: file.etag, "Content-Type": "application/json" },
      });
    }
    if (method === "PUT") {
      const bodyText = typeof init?.body === "string" ? init.body : "";
      this.files.set(path, { data: JSON.parse(bodyText), etag: this.nextEtag() });
      this.putPaths.push(path);
      return new Response(null, { status: 204 });
    }
    return new Response("", { status: 405 });
  };

  private pathFromInput(input: Parameters<typeof fetch>[0]): string {
    const url = new URL(String(input));
    const marker = "/HyperBrowserSync/";
    const index = url.pathname.indexOf(marker);
    return index >= 0 ? decodeURIComponent(url.pathname.slice(index + marker.length)) : "";
  }

  private nextEtag(): string {
    this.etagCounter += 1;
    return `"etag-${this.etagCounter}"`;
  }
}
