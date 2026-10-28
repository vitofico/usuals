import test from "node:test";
import assert from "node:assert/strict";
import { RunScope, request } from "../src/client.mjs";

async function withFetch(fake, run) {
  const original = globalThis.fetch;
  globalThis.fetch = fake;
  try { await run(); } finally { globalThis.fetch = original; }
}

test("fetch transport keeps same-origin cookie, CSRF, no-store, redirects disabled and bounded body", async () => {
  let observed;
  await withFetch(async (path, options) => {
    observed = { path, options };
    return new Response('{"ok":true}', { status: 200 });
  }, async () => {
    assert.deepEqual(await request("/api/local-test", { method: "POST", body: { synthetic: true }, csrf: "csrf" }), { ok: true });
    assert.equal(observed.options.credentials, "same-origin");
    assert.equal(observed.options.redirect, "error");
    assert.equal(observed.options.cache, "no-store");
    assert.equal(observed.options.headers["X-CSRF-Token"], "csrf");
    assert.equal(observed.options.headers.Authorization, undefined);
    assert.equal(observed.options.body, '{"synthetic":true}');
    await assert.rejects(request("//elsewhere.invalid"));
    await assert.rejects(request("/\\other.invalid"));
    await assert.rejects(request("/\n/other.invalid"));
  });
  await withFetch(async () => new Response("123456789", { status: 200 }), () =>
    assert.rejects(request("/test", { max: 4 }), (error) => error.code === "too_large"));
});

test("a route may answer with any of its expected statuses, and 204 has no body", async () => {
  for (const status of [200, 201])
    await withFetch(async () => new Response('{"id":"x"}', { status }), async () =>
      assert.deepEqual(await request("/api/lists/x", { method: "PUT", body: {}, status: [200, 201] }), { id: "x" }));
  await withFetch(async () => new Response(null, { status: 204 }), async () =>
    assert.equal(await request("/api/lists/x", { method: "DELETE", status: 204 }), null));
});

test("server error codes the interface explains survive, any 401 means signed out, anything else reads as invalid", async () => {
  for (const [status, body, code] of [[409, '{"code":"list_exists"}', "list_exists"], [409, '{"code":"limit_reached"}', "limit_reached"],
    [401, '{"code":"unauthenticated"}', "unauthenticated"], [404, '{"code":"not_found"}', "not_found"], [413, '{"code":"too_large"}', "too_large"],
    [401, "", "unauthenticated"], [500, '{"code":"database_exploded"}', "invalid"], [502, "<html>Bad gateway</html>", "invalid"]])
    await withFetch(async () => new Response(body, { status }), () =>
      assert.rejects(request("/api/lists/x/intents", { method: "POST", body: {} }), (error) => error.code === code));
  await withFetch(async () => new Response("not json", { status: 200 }), () =>
    assert.rejects(request("/api/me"), (error) => error.code === "invalid"));
});

test("transport times out stalled fetches, cancels oversized streams and stops with its scope", async () => {
  const stalled = async (_path, options) => new Promise((_resolve, reject) =>
    options.signal.addEventListener("abort", () => reject(new DOMException("Aborted", "AbortError")), { once: true }));
  await withFetch(stalled, async () => {
    await assert.rejects(request("/stalled", { timeoutMs: 5 }), (error) => error.code === "unavailable");
    const scope = new RunScope(), pending = request("/api/lists/changes?wait=25", { scope, timeoutMs: 35000 });
    scope.stop();
    await assert.rejects(pending, (error) => error.code === "locked");
    await assert.rejects(request("/stalled", { timeoutMs: 45001 }));
  });
  let cancelled = false;
  await withFetch(async () => new Response(new ReadableStream({
    start(controller) { controller.enqueue(new Uint8Array(100)); },
    cancel() { cancelled = true; },
  })), () => assert.rejects(request("/too-large", { max: 10 }), (error) => error.code === "too_large"));
  assert.equal(cancelled, true);
});

// These codes have dedicated recovery flows or translations in the browser.
test("invitation and account errors retain their structured recovery code", async () => {
  for (const [status, code] of [[410, "invitation_unavailable"], [403, "invitation_wrong_account"],
    [401, "reauthentication_required"], [403, "email_verification_required"], [403, "invitation_required"],
    [429, "invitation_resend_later"], [400, "invalid_email"], [409, "account_switch_unavailable"]]) {
    await withFetch(async () => Response.json({ code }, { status }), () =>
      assert.rejects(request("/api/invitations/context", { method: "POST", body: {}, status: 204 }),
        error => error.code === code));
  }
});
