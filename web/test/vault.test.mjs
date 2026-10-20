import test from "node:test";
import assert from "node:assert/strict";
import {
  Vault,
  derive,
  encryptRecord,
  decryptRecord,
  vaultInitial,
} from "../src/vault.mjs";
import { random } from "../src/crypto.mjs";
const pass = "synthetic long browser passphrase",
  binding = JSON.stringify([
    "https://fixture.invalid",
    "urn:usuals:local",
    "immutable-subject",
    "account-uuid",
  ]);
function locks() {
  let held = false;
  return {
    get held() {
      return held;
    },
    request: async (_name, _options, callback) => {
      if (held) return callback(null);
      held = true;
      try {
        return await callback({ name: "usuals-encrypted-vault" });
      } finally {
        held = false;
      }
    },
  };
}
function memory() {
  let record;
  return {
    read: async () =>
      record === undefined ? undefined : structuredClone(record),
    write: async (value) => {
      record = structuredClone(value);
    },
    get record() {
      return record;
    },
  };
}
const tick = () => new Promise((resolve) => setImmediate(resolve));
test("vault encrypted record rejects wrong passphrase, account and tampering", async () => {
  const salt = random(16),
    key = await derive(pass, salt),
    state = vaultInitial();
  state.joining = { privateFixture: "do-not-store-in-clear" };
  const record = await encryptRecord(state, key, salt, binding);
  assert.equal(JSON.stringify(record).includes("do-not-store-in-clear"), false);
  assert.deepEqual(await decryptRecord(record, key, binding), state);
  await assert.rejects(
    decryptRecord(
      record,
      await derive("another synthetic strong passphrase", salt),
      binding,
    ),
  );
  await assert.rejects(decryptRecord(record, key, binding + "other"));
  await assert.rejects(
    decryptRecord(
      { ...record, binding: binding + "other" },
      key,
      binding + "other",
    ),
  );
  await assert.rejects(derive("short", salt));
});
test("exclusive lock spans decrypt and the entire unlocked session", async () => {
  const storage = memory(),
    lockManager = locks(),
    first = new Vault(storage, binding),
    second = new Vault(storage, binding);
  await first.unlock(pass, lockManager);
  assert.equal(lockManager.held, true);
  await assert.rejects(
    second.unlock(pass, lockManager),
    (error) => error.code === "vault_in_use",
  );
  await first.reserve("scope");
  assert.equal(first.read().reservations.scope, 1);
  first.lock();
  await tick();
  await second.unlock(pass, lockManager);
  assert.equal(second.read().reservations.scope, 1);
  second.lock();
  await tick();
});
test("a late unlock never restores keys after locking", async () => {
  let resolve;
  const storage = {
      read: () =>
        new Promise((r) => {
          resolve = r;
        }),
      write: async () => {
        throw new Error("unexpected");
      },
    },
    manager = locks(),
    vault = new Vault(storage, binding),
    pending = vault.unlock(pass, manager);
  await tick();
  vault.lock();
  resolve(undefined);
  await assert.rejects(pending, (error) => error.code === "locked");
  assert.equal(vault.key, null);
  assert.equal(vault.state, null);
  await tick();
  assert.equal(manager.held, false);
});
test("lock is retained until an already-started durable write drains", async () => {
  const base = memory(),
    manager = locks();
  let hold = false,
    finish,
    start;
  const started = new Promise((r) => {
      start = r;
    }),
    storage = {
      read: base.read,
      write: async (record) => {
        if (hold) {
          start();
          await new Promise((r) => {
            finish = r;
          });
        }
        await base.write(record);
      },
    };
  const vault = new Vault(storage, binding);
  await vault.unlock(pass, manager);
  hold = true;
  const pending = vault.reserve("scope");
  await started;
  vault.lock();
  assert.equal(manager.held, true);
  finish();
  await assert.rejects(pending, (error) => error.code === "locked");
  await tick();
  assert.equal(manager.held, false);
  assert.equal(vault.key, null);
  assert.equal(vault.state, null);
});
test("failed storage locks the vault and reservation limit survives reopen", async () => {
  const storage = memory(),
    manager = locks(),
    vault = new Vault(storage, binding);
  await vault.unlock(pass, manager);
  await vault.mutate((s) => {
    s.reservations.scope = 4096;
  });
  await assert.rejects(
    vault.reserve("scope"),
    (error) => error.code === "rotation_required",
  );
  assert.equal(vault.read().reservations.scope, 4096);
  storage.write = async () => {
    throw new Error("disk");
  };
  await assert.rejects(
    vault.reserve("other"),
    (error) => error.code === "storage_unavailable",
  );
  assert.equal(vault.key, null);
  await tick();
  assert.equal(manager.held, false);
});

test("the tab lock also drains stopped network and crypto operations", async () => {
  const storage = memory(),
    manager = locks(),
    vault = new Vault(storage, binding);
  await vault.unlock(pass, manager);
  let finish;
  const active = new Promise((resolve) => {
    finish = resolve;
  });
  vault.lock(active);
  await tick();
  assert.equal(manager.held, true);
  assert.equal(vault.key, null);
  finish();
  await tick();
  assert.equal(manager.held, false);
});

test("constructor preference and literal numbers survive encrypted lock and reopen", async () => {
  const { emptyProjection, reduce } = await import("../src/reducer.mjs"),
    { strictJson, jsonBytes, text, equal } = await import("../src/wire.mjs"),
    storage = memory(),
    manager = locks(),
    vault = new Vault(storage, binding);
  await vault.unlock(pass, manager);
  const before = emptyProjection(),
    result = await reduce(before, {
      id: "pref",
      listId: "list",
      kind: "preference",
      schema: 1,
      normalizer: 1,
      payload: {
        key: "constructor",
        expected: null,
        changes: { category: "PANTRY" },
      },
    });
  assert.equal(result.outcome, "APPLIED");
  const lexical = strictJson(
    '{"position":0.0,"quantity":1.0,"other":1e0}',
    1024,
    true,
  );
  await vault.mutate((s) => {
    s.projection = result.projection;
    s.lexical = lexical;
  });
  vault.lock();
  await tick();
  await vault.unlock(pass, manager);
  assert.equal(
    vault.read().projection.preferences.constructor.category,
    "PANTRY",
  );
  assert.equal(
    text.decode(jsonBytes(vault.read().lexical)),
    '{"position":0.0,"quantity":1.0,"other":1e0}',
  );
  assert.equal(equal(vault.read().lexical, lexical), true);
  vault.lock();
  await tick();
});

test("unreadable aggregate depth and node count are rejected before replacing a durable vault", async () => {
  const storage = memory(),
    manager = locks(),
    vault = new Vault(storage, binding);
  await vault.unlock(pass, manager);
  await vault.mutate((s) => {
    s.kept = "existing durable state";
  });
  const original = structuredClone(storage.record);
  let deep = {};
  for (let i = 0; i < 34; i++) deep = { child: deep };
  await assert.rejects(
    vault.mutate((s) => {
      s.aggregate = deep;
    }),
  );
  assert.deepEqual(storage.record, original);
  assert.equal(vault.read().kept, "existing durable state");
  assert.equal(vault.read().aggregate, undefined);
  await assert.rejects(
    vault.mutate((s) => {
      s.aggregate = new Array(1000000).fill(true);
    }),
  );
  assert.deepEqual(storage.record, original);
  vault.lock();
  await tick();
  await vault.unlock(pass, manager);
  assert.equal(vault.read().kept, "existing durable state");
  assert.equal(vault.read().aggregate, undefined);
  vault.lock();
  await tick();
});
