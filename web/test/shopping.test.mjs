import test from "node:test";
import assert from "node:assert/strict";
import { DAY, reviewCandidates, historyTrips, addAgain, unchangedRow, guardedRemoval, ShoppingChoices } from "../src/shopping.mjs";

const now = 100 * DAY;
const projection = (extra = {}) => ({ rows: {}, tombstones: {}, aliases: {}, trips: {}, purchases: {}, preferences: {}, ...extra });
const row = (id, key, extra = {}) => ({ id, facts: { key, name: key, checked: false, quantity: "2.0", unit: "PIECE", ...extra }, lifecycle: "capture", revisions: { name: "capture", quantity: "capture", check: "capture" } });
const tripHistory = () => projection({ trips: { old: { closedAt: 80 * DAY }, recent: { closedAt: 90 * DAY } }, purchases: {
  one: { key: "milk", name: "Milk", tripId: "old", boughtAt: 80 * DAY },
  two: { key: "milk", name: "Milk", tripId: "recent", boughtAt: 90 * DAY },
  three: { key: "bread", name: "Bread", tripId: "recent", boughtAt: 90 * DAY },
} });
test("Replay follows Android's early-history, frequency and oldest-purchase ordering", () => {
  const p = tripHistory();
  assert.deepEqual(reviewCandidates(p, now).map((item) => [item.key, item.shopCount, item.windowSize]), [["milk", 2, 2], ["bread", 1, 2]]);
  p.trips.third = { closedAt: 95 * DAY };
  assert.deepEqual(reviewCandidates(p, now).map((item) => item.key), ["milk"]);
  p.trips.future = { closedAt: 110 * DAY };
  assert.equal(reviewCandidates(p, now)[0].windowSize, 3);
});
test("Replay excludes checked and unchecked rows, repeats, aliases, muted and very recent purchases", () => {
  const p = tripHistory();
  p.aliases = { leche: "milk" };
  p.rows.leche = row("leche", "leche", { checked: true });
  assert.deepEqual(reviewCandidates(p, now).map((item) => item.key), ["bread"]);
  delete p.rows.leche;
  p.preferences.leche = { repeatEveryDays: 7 };
  assert.deepEqual(reviewCandidates(p, now, ["bread"]).map((item) => item.key), []);
  p.preferences = {};
  p.purchases.two.boughtAt = now - DAY + 1;
  assert.deepEqual(reviewCandidates(p, now).map((item) => item.key), ["bread"]);
});
test("Replay uses only eight completed trips and groups named barcode aliases", () => {
  const p = tripHistory();
  p.aliases = { "gtin:4006381333931": "milk" };
  p.purchases.two = { ...p.purchases.two, key: "other", gtin: "4006381333931" };
  assert.equal(reviewCandidates(p, now)[0].shopCount, 2);
  for (let index = 0; index < 8; index++) p.trips["new-" + index] = { closedAt: (91 + index) * DAY };
  assert.deepEqual(reviewCandidates(p, now), []);
});
test("history shows actual purchase groups, searches translated names and respects finish undo", () => {
  const p = tripHistory();
  assert.deepEqual(historyTrips(p).map((trip) => [trip.id, trip.items.length]), [["recent", 2], ["old", 1]]);
  assert.deepEqual(historyTrips(p, "milk").map((trip) => trip.items.length), [1, 1]);
  p.purchases = {}; p.trips = {};
  assert.deepEqual(historyTrips(p), []);
});
test("add again preserves metadata, skips aliases without increasing quantity, and permits checked items", () => {
  const p = projection({ rows: { milk: row("milk", "milk"), bread: row("bread", "bread", { checked: true }) }, aliases: { leche: "milk" } });
  const payloads = addAgain(p, [{ name: "Leche", key: "leche", quantity: "8.0", unit: "LITRE" },
    { name: "Bread", quantity: "1.0", unit: "PACK", gtin: "4006381333931", store: "Bakery", note: "Rye" }, { name: "Bread" }], now);
  assert.equal(payloads.length, 1);
  assert.deepEqual([payloads[0].key, payloads[0].quantity, payloads[0].unit, payloads[0].store, payloads[0].gtin, payloads[0].note],
    ["bread", "1.0", "PACK", "Bakery", "4006381333931", "Rye"]);
  assert.equal(p.rows.milk.facts.quantity, "2.0");
});
test("Replay undo and moves require the complete original version, including formerly absent fields", () => {
  const saved = row("milk", "milk");
  const p = projection({ rows: { milk: saved } });
  assert.equal(unchangedRow(p, structuredClone(saved)), saved);
  const expected = guardedRemoval(saved, now).expected;
  assert.equal(expected.store, null);
  assert.equal(expected.check, "capture");
  assert.equal(expected.lifecycle, "capture");
  p.rows.milk = { ...saved, facts: { ...saved.facts, checked: true }, revisions: { ...saved.revisions, check: "phone" } };
  assert.equal(unchangedRow(p, saved), null);
  p.rows.milk = saved; p.tombstones.milk = "gone";
  assert.equal(unchangedRow(p, saved), null);
});
test("personal choices stay scoped, survive reload and fail visibly if browser storage is unavailable", () => {
  const values = new Map(), storage = { getItem: (key) => values.get(key), setItem: (key, value) => values.set(key, value) };
  const first = new ShoppingChoices(storage, "alice:list");
  first.write(() => ({ favorites: [{ name: "Bread", key: "bread", quantity: null }], muted: ["milk"] }));
  assert.equal(new ShoppingChoices(storage, "alice:list").read().favorites[0].name, "Bread");
  assert.deepEqual(new ShoppingChoices(storage, "bob:list").read(), { favorites: [], muted: [] });
  assert.throws(() => new ShoppingChoices(null, "alice:list").write((value) => value), { code: "shoppingStorageUnavailable" });
});
