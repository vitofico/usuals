import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { join } from "node:path";
import {
  HouseholdClient,
  RunScope,
  request,
  parseInvitation,
} from "../src/client.mjs";
import {
  manifestHash,
  unb64,
  strictJson,
  zero,
  Problem,
  jsonBytes,
  equal,
} from "../src/wire.mjs";
import { body } from "../src/membership.mjs";
import { open } from "../src/crypto.mjs";
import { snapshotDecode, reduce } from "../src/reducer.mjs";
const directory = process.env.COMANDA_WEB_INTEROP_DIR;
async function setup() {
  const f = JSON.parse(
      await readFile(join(directory, "kotlin-input.json"), "utf8"),
    ),
    proofs = [f.genesis, f.created, f.approved],
    old = body(proofs[1]).members[0].devices[0],
    snapshot = await snapshotDecode(
      strictJson(
        await open(
          f.snapshot.envelope,
          unb64(f.key, 32),
          old.signingPublicKey,
          10 * 1024 * 1024,
        ),
        7 * 1024 * 1024,
        true,
      ),
    ),
    projection = (
      await reduce(snapshot, strictJson(unb64(f.plaintext), 65536, true))
    ).projection;
  let state = {
    version: 1,
    households: {
      [f.householdId]: {
        id: f.householdId,
        origin: f.origin,
        server: f.server,
        device: f.recipient,
        proofs,
        keys: { [`${f.listId}:3`]: f.key },
        headPins: { [f.listId]: { seq: f.receipt.seq, head: f.receipt.head } },
        receiptPins: {},
        lists: {
          [f.listId]: {
            confirmed: {
              seq: f.receipt.seq,
              head: f.receipt.head,
              projection,
              epoch: 3,
              manifestHash: await manifestHash(proofs.at(-1)),
            },
            pending: [],
          },
        },
        session: {
          token: "synthetic-session",
          expiresAt: Date.now() + 3600000,
        },
        removed: false,
        linked: true,
      },
    },
    joining: null,
    reservations: {},
    writes: 0,
  };
  const vault = {
    read: () => structuredClone(state),
    mutate: async (change) => {
      const draft = structuredClone(state);
      change(draft);
      draft.writes++;
      state = draft;
      return structuredClone(state);
    },
    reserve: async (scope) => {
      state.reservations[scope] = (state.reservations[scope] ?? 0) + 1;
    },
  };
  return {
    f,
    vault,
    get state() {
      return state;
    },
    set state(next) {
      state = next;
    },
  };
}
function transportFor(f, append) {
  return async (path, options) => {
    if (path.includes("/membership?")) return { manifests: [], nextEpoch: 3 };
    if (path.includes("/operations?"))
      return { entries: [], nextCursor: f.receipt.seq, hasMore: false };
    if (path.endsWith("/operations") && options.method === "POST")
      return append(options);
    throw new Error("Unexpected synthetic route: " + path);
  };
}
test(
  "durable envelope retries exact bytes after lost acknowledgement and reload",
  { skip: !directory },
  async () => {
    const fixture = await setup(),
      { f, vault } = fixture,
      submissions = [],
      transport = transportFor(f, (options) => {
        submissions.push(JSON.stringify(options.body));
        throw new Problem("unavailable");
      });
    const c = new HouseholdClient(
      vault,
      { csrfToken: "synthetic-csrf" },
      { origin: f.origin, transport },
    );
    await c.enqueue(f.householdId, f.listId, "capture", {
      proposedId: crypto.randomUUID(),
      key: "browser milk",
      name: "Browser milk",
      quantity: null,
      unit: null,
      createdAt: 1,
      updatedAt: 1,
      normalizerVersion: 1,
    });
    await assert.rejects(
      c.synchronize(f.householdId, f.listId),
      (error) => error.code === "unavailable",
    );
    const pending =
      vault.read().households[f.householdId].lists[f.listId].pending;
    assert.equal(pending.length, 1);
    assert.equal(typeof pending[0].envelope, "string");
    c.stop();
    const reopened = new HouseholdClient(
      vault,
      { csrfToken: "synthetic-csrf" },
      { origin: f.origin, transport },
    );
    await assert.rejects(
      reopened.synchronize(f.householdId, f.listId),
      (error) => error.code === "unavailable",
    );
    assert.equal(submissions.length, 2);
    assert.equal(submissions[0], submissions[1]);
    assert.deepEqual(Object.values(vault.read().reservations), [1]);
    assert.equal(
      vault.read().households[f.householdId].lists[f.listId].pending.length,
      1,
    );
    reopened.stop();
  },
);
test(
  "late membership fetch cannot mutate pins after scope is stopped",
  { skip: !directory },
  async () => {
    const fixture = await setup(),
      { f, vault } = fixture;
    let finish, started;
    const requestStarted = new Promise((resolve) => {
        started = resolve;
      }),
      transport = async () => {
        started();
        return new Promise((resolve) => {
          finish = resolve;
        });
      },
      scope = new RunScope(),
      client = new HouseholdClient(
        vault,
        { csrfToken: "csrf" },
        { origin: f.origin, transport, scope },
      ),
      before = vault.read(),
      pending = client.synchronize(f.householdId, f.listId);
    await requestStarted;
    client.stop();
    finish({ manifests: [], nextEpoch: 3 });
    await assert.rejects(pending, (error) => error.code === "locked");
    assert.deepEqual(vault.read(), before);
  },
);
test(
  "snapshot rollback stops without clearing pending intents or receipt witnesses",
  { skip: !directory },
  async () => {
    const fixture = await setup(),
      { f, vault } = fixture;
    await vault.mutate((s) => {
      const h = s.households[f.householdId];
      h.lists[f.listId].confirmed.manifestHash = zero;
      h.receiptPins[f.listId] = { [f.receipt.seq]: f.receipt.head };
      h.lists[f.listId].pending.push({
        intent: {
          id: crypto.randomUUID(),
          listId: f.listId,
          kind: "delete",
          schema: 1,
          normalizer: 1,
          payload: {
            target: crypto.randomUUID(),
            expected: { lifecycle: null },
          },
        },
        envelope: null,
        blocked: null,
      });
    });
    const before = vault.read(),
      transport = async (path) => {
        if (path.includes("/membership?"))
          return { manifests: [], nextEpoch: 3 };
        if (path.endsWith("/snapshot")) return f.snapshot;
        if (path.includes("/operations?"))
          return { entries: [], nextCursor: f.snapshot.seq, hasMore: false };
        throw new Error("Unexpected route");
      },
      client = new HouseholdClient(
        vault,
        { csrfToken: "csrf" },
        { origin: f.origin, transport },
      );
    await assert.rejects(
      client.synchronize(f.householdId, f.listId),
      (error) => error.code === "history_unverifiable",
    );
    assert.deepEqual(vault.read(), before);
  },
);
test(
  "same intent id with different source payload remains blocked and reviewable",
  { skip: !directory },
  async () => {
    const fixture = await setup(),
      { f, vault } = fixture,
      accepted = strictJson(unb64(f.plaintext), 65536, true);
    await vault.mutate((s) => {
      s.households[f.householdId].lists[f.listId].pending.push({
        intent: {
          ...accepted,
          payload: { ...accepted.payload, name: "Different source intent" },
        },
        envelope: null,
        blocked: null,
      });
    });
    const client = new HouseholdClient(
      vault,
      { csrfToken: "csrf" },
      {
        origin: f.origin,
        transport: transportFor(f, () => {
          throw new Error("Blocked intent must never upload");
        }),
      },
    );
    await client.synchronize(f.householdId, f.listId);
    const pending =
      vault.read().households[f.householdId].lists[f.listId].pending;
    assert.equal(pending.length, 1);
    assert.equal(pending[0].blocked, "intent_conflict");
  },
);
test("fetch transport keeps same-origin cookie, CSRF, no-store, redirects disabled and bounded body", async () => {
  const original = globalThis.fetch;
  try {
    let observed;
    globalThis.fetch = async (path, options) => {
      observed = { path, options };
      return new Response('{"ok":true}', { status: 200 });
    };
    assert.deepEqual(
      await request("/api/local-test", {
        method: "POST",
        body: { synthetic: true },
        token: "token",
        csrf: "csrf",
      }),
      { ok: true },
    );
    assert.equal(observed.options.credentials, "same-origin");
    assert.equal(observed.options.redirect, "error");
    assert.equal(observed.options.cache, "no-store");
    assert.equal(observed.options.headers["X-CSRF-Token"], "csrf");
    await assert.rejects(request("//elsewhere.invalid"));
    globalThis.fetch = async () => new Response("123456789", { status: 200 });
    await assert.rejects(
      request("/test", { max: 4 }),
      (error) => error.code === "too_large",
    );
  } finally {
    globalThis.fetch = original;
  }
});

test("transport preserves created account secrets and accepts empty mutation responses", async () => {
  const original = globalThis.fetch;
  const created = {
    user: { id: "synthetic-local-user" },
    setupToken: "synthetic-one-use-secret",
    expiresAt: 123456789,
  };
  try {
    globalThis.fetch = async () =>
      new Response(JSON.stringify(created), { status: 201 });
    assert.deepEqual(
      await request("/api/admin/local-users", {
        method: "POST",
        body: { username: "synthetic", displayName: "Synthetic person" },
        csrf: "synthetic-csrf",
        status: 201,
      }),
      created,
    );
    globalThis.fetch = async () => new Response(null, { status: 204 });
    assert.equal(
      await request("/api/sessions/synthetic/revoke", {
        method: "POST",
        csrf: "synthetic-csrf",
        status: 204,
      }),
      null,
    );
  } finally {
    globalThis.fetch = original;
  }
});

test("transport times out stalled fetches and cancels oversized streams", async () => {
  const original = globalThis.fetch;
  try {
    globalThis.fetch = async (_path, options) =>
      new Promise((_resolve, reject) =>
        options.signal.addEventListener(
          "abort",
          () => reject(new DOMException("Aborted", "AbortError")),
          { once: true },
        ),
      );
    await assert.rejects(
      request("/stalled", { timeoutMs: 5 }),
      (error) => error.code === "unavailable",
    );
    let cancelled = false;
    globalThis.fetch = async () =>
      new Response(
        new ReadableStream({
          start(controller) {
            controller.enqueue(new Uint8Array(100));
          },
          cancel() {
            cancelled = true;
          },
        }),
      );
    await assert.rejects(
      request("/too-large", { max: 10 }),
      (error) => error.code === "too_large",
    );
    assert.equal(cancelled, true);
    await assert.rejects(request("/\\other.invalid"));
    await assert.rejects(request("/\n/other.invalid"));
  } finally {
    globalThis.fetch = original;
  }
});

test(
  "browser editor rejects owner invitation before creating device keys",
  { skip: !directory },
  async () => {
    const { f, vault } = await setup(),
      invitation = { ...f.invitation, role: "owner" },
      uri =
        "comanda://join#" +
        btoa(JSON.stringify(invitation))
          .replaceAll("+", "-")
          .replaceAll("/", "_")
          .replaceAll("=", "");
    assert.throws(() => parseInvitation(uri, f.origin));
    const client = new HouseholdClient(
      vault,
      { csrfToken: "csrf" },
      {
        origin: f.origin,
        transport: () => {
          throw new Error("Owner invitation must stop before network");
        },
      },
    );
    await assert.rejects(client.requestJoin(uri));
    assert.equal(vault.read().joining, null);
  },
);

test(
  "resolved acknowledgement witness survives truncated catchup and reload",
  { skip: !directory },
  async () => {
    const fixture = await setup(),
      { f, vault } = fixture,
      { encodeHeader, b64, parseHeader } = await import("../src/wire.mjs"),
      intent = strictJson(unb64(f.plaintext), 65536, true),
      oldHeader = {
        ...parseHeader(f.envelope.header),
        epoch: 2,
        manifestHash: await manifestHash(f.created),
      },
      pendingEnvelope = { ...f.envelope, header: b64(encodeHeader(oldHeader)) };
    await vault.mutate((s) => {
      const h = s.households[f.householdId];
      h.keys[`${f.listId}:2`] = f.key;
      h.headPins[f.listId] = { seq: 0, head: zero };
      const local = h.lists[f.listId];
      local.confirmed.seq = 0;
      local.confirmed.head = zero;
      local.confirmed.projection.rows = {};
      local.confirmed.projection.seen = {};
      local.pending = [
        { intent, envelope: JSON.stringify(pendingEnvelope), blocked: null },
      ];
    });
    const transport = async (path) => {
        if (path.includes("/membership?"))
          return { manifests: [], nextEpoch: 3 };
        if (path.includes("/operations?"))
          return { entries: [], nextCursor: 0, hasMore: false };
        if (path.includes("/intents/"))
          return { envelope: f.envelope, receipt: f.receipt };
        throw new Error("Unexpected synthetic route");
      },
      client = new HouseholdClient(
        vault,
        { csrfToken: "csrf" },
        { origin: f.origin, transport },
      );
    await assert.rejects(
      client.synchronize(f.householdId, f.listId),
      (error) => error.code === "history_unverifiable",
    );
    const saved = vault.read().households[f.householdId];
    assert.equal(saved.receiptPins[f.listId][f.receipt.seq], f.receipt.head);
    assert.equal(saved.lists[f.listId].pending.length, 1);
    client.stop();
    const reloaded = new HouseholdClient(
      vault,
      { csrfToken: "csrf" },
      { origin: f.origin, transport },
    );
    await assert.rejects(
      reloaded.synchronize(f.householdId, f.listId),
      (error) => error.code === "history_unverifiable",
    );
    assert.equal(
      vault.read().households[f.householdId].receiptPins[f.listId][
        f.receipt.seq
      ],
      f.receipt.head,
    );
  },
);

test(
  "pending join retries the same signed submission after failure before or after receipt",
  { skip: !directory },
  async () => {
    for (const firstReachedServer of [false, true]) {
      const fixture = await setup(),
        { f, vault } = fixture;
      await vault.mutate((s) => {
        s.households = {};
      });
      const submissions = [];
      let calls = 0,
        accepted = false;
      const transport = async (path, options) => {
          if (path === "/v1/server-info") return f.server;
          if (path === "/v1/devices/challenge")
            return {
              challengeId: crypto.randomUUID(),
              nonce: f.joinAssertion.nonce,
              serverId: f.server.serverId,
              expiresAt: Date.now() + 60000,
              purpose: options.body.purpose,
            };
          if (path === "/v1/devices/session") throw new Problem("forbidden");
          if (path === "/v1/join-requests") {
            submissions.push(JSON.stringify(options.body));
            calls++;
            if (calls === 1) {
              accepted = firstReachedServer;
              throw new Problem("unavailable");
            }
            accepted = true;
            const pending = vault.read().joining;
            return {
              requestId: pending.assertion.requestId,
              expiresAt: pending.assertion.expiresAt,
            };
          }
          throw new Error("Unexpected join route");
        },
        client = new HouseholdClient(
          vault,
          { csrfToken: "csrf" },
          { origin: f.origin, transport },
        );
      const { b64 } = await import("../src/wire.mjs"),
        uri = "comanda://join#" + b64(jsonBytes(f.invitation));
      await assert.rejects(
        client.requestJoin(uri),
        (error) => error.code === "unavailable",
      );
      const saved = vault.read().joining;
      assert.equal(await client.completeJoin(), null);
      assert.equal(accepted, true);
      assert.equal(submissions.length, 2);
      assert.equal(submissions[0], submissions[1]);
      assert.deepEqual(vault.read().joining.device, saved.device);
      client.stop();
    }
  },
);
