import test from "node:test";
import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import { validatePacks, packFacts, mergePacks, readPackArchive } from "../src/occasion-packs.mjs";
import { ShoppingChoices, addAgain } from "../src/shopping.mjs";
globalThis.crypto ??= webcrypto;
const pack = { id: "picnic", name: "Picnic", items: [{ id: "milk", name: "Whole milk", amount: 2, unit: "LITRE", note: "For visitors", store: "Market", category: "DAIRY" }] };
const p = { rows: { milk: { id: "milk", facts: { name: "Milk", key: "milk", quantity: "3.0", unit: "LITRE", checked: true } } }, aliases: { "whole milk": "milk" }, tombstones: {} };
test("packs round trip with details and support Android full archives and browser pack exports", () => {
  assert.deepEqual(readPackArchive(JSON.stringify({ formatVersion: 1, occasionPacks: [pack] })), [pack]);
  assert.deepEqual(readPackArchive(JSON.stringify({ formatVersion: 4, personalShopping: { packs: [pack] } })), [pack]);
  assert.deepEqual(mergePacks([pack], [pack]), [pack]);
  assert.throws(() => mergePacks([pack], [{ ...pack, name: "Changed" }]));
  assert.deepEqual(packFacts(pack.items[0]), { ...pack.items[0], quantity: "2.0" });
});
test("pack review and confirmation skip checked aliases while ordinary reuse can buy checked rows again", () => {
  assert.equal(addAgain(p, pack.items.map(packFacts), 1, true).length, 0);
  assert.equal(addAgain(p, pack.items.map(packFacts), 1).length, 1);
  const unchecked = structuredClone(p); unchecked.rows.milk.facts.checked = false;
  assert.equal(addAgain(unchecked, pack.items.map(packFacts), 1, true).length, 0);
  const empty = { rows: {}, aliases: {}, tombstones: {} };
  const payload = addAgain(empty, [packFacts(pack.items[0]), packFacts(pack.items[0])], 1, true);
  assert.equal(payload.length, 1); assert.equal(payload[0].note, "For visitors"); assert.equal(payload[0].store, "Market");
  assert.equal(payload[0].quantity, "2.0");
});
test("local pack preferences persist and isolate accounts and lists without discarding old favorites", () => {
  const data = new Map(), storage = { getItem: key => data.get(key) ?? null, setItem: (key, value) => data.set(key, value) };
  const alice = new ShoppingChoices(storage, "alice:list");
  alice.write(value => ({ ...value, favorites: [{ name: "Bread" }], packs: [pack] }));
  assert.deepEqual(new ShoppingChoices(storage, "alice:list").read().packs, [pack]);
  assert.equal(new ShoppingChoices(storage, "bob:list").read().packs, undefined);
  assert.equal(new ShoppingChoices(storage, "alice:other-list").read().packs, undefined);
  assert.throws(() => alice.write(value => ({ ...value, packs: [{ ...pack, name: " " }] })));
  assert.deepEqual(alice.read().packs, [pack]);
  data.set(alice.key, "broken");
  assert.throws(() => alice.write(value => value), { code: "shoppingStorageUnavailable" });
  assert.equal(data.get(alice.key), "broken");
});
test("bounds and quantities reject invalid imports without guessing units or conversions", () => {
  for (const item of [{ ...pack.items[0], amount: Infinity }, { ...pack.items[0], unit: "cup" }, { ...pack.items[0], amount: null }, { ...pack.items[0], note: "x".repeat(1001) }]) {
    assert.throws(() => validatePacks([{ ...pack, items: [item] }]));
  }
  assert.throws(() => validatePacks([pack, pack]));
  assert.throws(() => validatePacks([{ ...pack, items: Array.from({ length: 201 }, (_, i) => ({ ...pack.items[0], id: String(i) })) }]));
});
