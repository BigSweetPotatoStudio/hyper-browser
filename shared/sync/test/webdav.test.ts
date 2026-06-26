import assert from "node:assert/strict";
import test from "node:test";
import { WebDavClient, WebDavConflictError } from "../src/webdav";

test("putJson fails new-file writes when the server rejects atomic create", async () => {
  const requests: Array<{ method: string; path: string; ifNoneMatch: string | null }> = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (async (input: Parameters<typeof fetch>[0], init?: Parameters<typeof fetch>[1]) => {
    const url = new URL(String(input));
    const method = String(init?.method || "GET").toUpperCase();
    const headers = new Headers(init?.headers);
    const path = decodeURIComponent(url.pathname);
    requests.push({ method, path, ifNoneMatch: headers.get("If-None-Match") });
    if (method === "MKCOL") return new Response("", { status: 405 });
    if (method === "GET") return new Response("", { status: 404 });
    if (method === "PUT" && headers.get("If-None-Match") === "*") {
      return new Response("conditional PUT not supported", { status: 405 });
    }
    if (method === "PUT") return new Response(null, { status: 204 });
    return new Response("", { status: 405 });
  }) as typeof fetch;

  try {
    const client = new WebDavClient({
      webDavUrl: "https://example.com/dav/backup",
      username: "",
      password: "",
    });
    await assert.rejects(
      () => client.putJson("bookmarks.json", { ok: true }, null, { ifNoneMatch: true }),
      /does not support atomic create/,
    );
  } finally {
    globalThis.fetch = originalFetch;
  }

  assert.deepEqual(
    requests.map((request) => [request.method, request.ifNoneMatch]),
    [
      ["PUT", "*"],
      ["GET", null],
    ],
  );
  assert.equal(requests[0].path, "/dav/backup/HyperBrowserSync/bookmarks.json");
});

test("putJson maps atomic-create rejection to a conflict when the remote file exists", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (async (_input: Parameters<typeof fetch>[0], init?: Parameters<typeof fetch>[1]) => {
    const method = String(init?.method || "GET").toUpperCase();
    const headers = new Headers(init?.headers);
    if (method === "GET") return new Response(JSON.stringify({ ok: false }), {
      status: 200,
      headers: { "Content-Type": "application/json", ETag: "\"remote\"" },
    });
    if (method === "PUT" && headers.get("If-None-Match") === "*") {
      return new Response("conditional PUT not supported", { status: 405 });
    }
    return new Response("", { status: 405 });
  }) as typeof fetch;

  try {
    const client = new WebDavClient({
      webDavUrl: "https://example.com/dav/backup",
      username: "",
      password: "",
    });
    await assert.rejects(
      () => client.putJson("bookmarks.json", { ok: true }, null, { ifNoneMatch: true }),
      (error) => error instanceof WebDavConflictError && error.status === 412,
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("ensureCollections fails when MKCOL 405 does not create the missing collection", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (async (_input: Parameters<typeof fetch>[0], init?: Parameters<typeof fetch>[1]) => {
    const method = String(init?.method || "GET").toUpperCase();
    if (method === "PROPFIND") return new Response("", { status: 404 });
    if (method === "MKCOL") return new Response("method not allowed", { status: 405 });
    return new Response("", { status: 405 });
  }) as typeof fetch;

  try {
    const client = new WebDavClient({
      webDavUrl: "https://example.com/dav/backup",
      username: "",
      password: "",
    });
    await assert.rejects(
      () => client.ensureCollections(),
      /WebDAV collection was not created/,
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});
