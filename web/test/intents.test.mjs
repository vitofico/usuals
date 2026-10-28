import test from "node:test";
import assert from "node:assert/strict";
import { canonical, decimal, rows, captures, check, edit, remove, rename, finish, undo, intent } from "../src/intents.mjs";

const row = (id, facts, extra = {}) => ({ id, facts: { key: id, name: id, quantity: null, unit: null, checked: false, ...facts },
  revisions: { name: "n-" + id, check: "c-" + id }, lifecycle: "l-" + id, archived: false, ...extra });
const projection = (list, extra = {}) => ({ rows: Object.fromEntries(list.map((r) => [r.id, r])), tombstones: {}, aliases: {},
  purchases: {}, name: "Groceries", nameRevision: "rev-name", ...extra });

test("decimals use Java's Double.toString spelling, as the reducer requires", () => {
  for (const [value, spelled] of [[0, "0.0"], [2, "2.0"], [1.5, "1.5"], [0.001, "0.001"], [0.0005, "5.0E-4"], [1e7, "1.0E7"],
    [12345678.9, "1.23456789E7"], [9999999, "9999999.0"]])
    assert.equal(decimal(value), spelled, String(value));
});

test("rows hide archived and deleted items and keep list order", () => {
  const p = projection([row("b", { position: 2 }), row("a", { position: 1, createdAt: 5 }), row("c", { position: 1, createdAt: 3 }),
    row("gone", { position: 0 }), row("bought", { position: 0 }, { archived: true })], { tombstones: { gone: "x" } });
  assert.deepEqual(rows(p).map((r) => r.id), ["c", "a", "b"]);
});

test("a capture carries the aliased canonical key and goes after the last item", () => {
  const p = projection([row("x", { position: 4 })], { aliases: { milk: "whole milk" } });
  const [payload] = captures(p, [{ name: "Milk" }], 1000);
  assert.match(payload.proposedId, /^[0-9a-f-]{36}$/);
  assert.deepEqual({ ...payload, proposedId: undefined }, { proposedId: undefined, key: "whole milk", name: "Milk", rawText: "Milk", note: null,
    quantity: null, unit: null, normalizerVersion: 1, createdAt: 1000, updatedAt: 1000, position: 5 });
  const imported = captures(projection([]), [{ key: "egg", name: "Eggs", rawText: "6 eggs", quantity: "6.0", unit: "PIECE", note: "free range" },
    { key: "salt", name: "Salt" }], 7);
  assert.deepEqual(imported.map((p) => [p.key, p.quantity, p.unit, p.position, p.rawText]), [["egg", "6.0", "PIECE", 0, "6 eggs"], ["salt", null, null, 1, "Salt"]]);
  assert.equal(canonical("a", { a: "b", b: "a" }), "a", "a cycle cannot hang the browser");
});

test("check, edit and delete are guarded by the revisions this browser saw", () => {
  const item = row("bread", { note: "rye" }, { revisions: { name: "n1", check: "c1", quantity: "q1" } });
  assert.deepEqual(check(item, true, 50), { target: "bread", expected: { check: "c1" }, checked: true, checkedAt: 50, updatedAt: 50 });
  assert.deepEqual(check(item, false, 60), { target: "bread", expected: { check: "c1" }, checked: false, checkedAt: null, updatedAt: 60 });
  assert.deepEqual(edit(item, { name: "Bread", key: "bread", quantity: "2.0", unit: "PIECE", store: "Bakery" }, 70), {
    target: "bread",
    changes: { name: "Bread", key: "bread", quantity: "2.0", unit: "PIECE", store: "Bakery", updatedAt: 70 },
    expected: { name: "n1", quantity: "q1", store: null },
  });
  assert.deepEqual(remove(item, 80), { target: "bread", expected: { lifecycle: "l-bread" }, deletedAt: 80, updatedAt: 80 });
  assert.deepEqual(remove({ ...item, lifecycle: undefined }, 80).expected, { lifecycle: null });
  assert.deepEqual(rename(projection([]), "Weekly shop"), { name: "Weekly shop", expected: "rev-name" });
  assert.deepEqual(rename(projection([], { nameRevision: undefined }), "First"), { name: "First", expected: null });
});

test("finishing sends picked-up items with facts that round-trip exactly, and undo names that finish", () => {
  const picked = row("milk", { checked: true, checkedAt: 1700000000000, quantity: "1.0", unit: "LITRE", position: 3.5,
    createdAt: 1, updatedAt: 2, note: null, gtin: "4006381333931", fromSuggestion: false, normalizerVersion: 1 });
  const p = projection([picked, row("eggs", {}), row("old", { checked: true }), row("odd", { checked: true, checkedAt: 1.5 })]);
  const payload = finish(p, 99);
  assert.equal(payload.closedAt, 99);
  assert.match(payload.tripId, /^[0-9a-f-]{36}$/);
  assert.deepEqual(payload.rows, [{
    id: "milk",
    facts: { key: "milk", name: "milk", quantity: "1.0", unit: "LITRE", checkedAt: 1700000000000, note: null, gtin: "4006381333931", fromSuggestion: false },
    expectedLifecycle: "l-milk",
    expectedFields: { name: "n-milk", check: "c-milk" },
  }]);
  assert.equal(finish(projection([row("eggs", {})]), 1), null);
  const sent = intent("L", "finish", payload);
  assert.deepEqual(undo(sent), { finishId: sent.id, tripId: payload.tripId });
});


test("finishing sparse quantityless rows preserves exactly the optional facts that exist", () => {
  for (const optional of [{}, { quantity: null }, { unit: null }, { quantity: null, unit: null }]) {
    const item = row("bread", { checked: true, checkedAt: 100 });
    delete item.facts.quantity;
    delete item.facts.unit;
    Object.assign(item.facts, optional);
    const payload = finish(projection([item]), 200);
    assert.deepEqual(payload.rows[0].facts, { key: "bread", name: "bread", checkedAt: 100, ...optional });
    assert.deepEqual(payload.rows[0].expectedFields, item.revisions);
    assert.equal(payload.rows[0].expectedLifecycle, item.lifecycle);
  }
});
