import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { shoppingStats, shoppingStatsSection } from "../src/shopping-stats.mjs";
import { DAY, reviewCandidates } from "../src/shopping.mjs";

const dictionary = JSON.parse(readFileSync(new URL("../locales/en.json", import.meta.url)));
const t = (key, values = {}) => dictionary[key].replace(/\{([a-z]+)\}/g, (_, name) => values[name]);
const projection = () => ({ rows: {}, tombstones: {}, aliases: {}, trips: {}, purchases: {}, preferences: {} });
function history(shops) {
  const p = projection();
  shops.forEach(([day, names], index) => {
    const id = `trip-${index}`; p.trips[id] = { closedAt: day * DAY };
    names.forEach((name, row) => p.purchases[`${index}-${row}`] = { tripId: id, key: name, name, boughtAt: day * DAY });
  });
  return p;
}
test("empty statistics contain no invented facts", () => {
  assert.deepEqual(shoppingStats(projection(), 100 * DAY), { shopCount: 0, purchaseCount: 0, differentItems: 0, recentShopCount: 0, usuals: [] });
});
test("aliases group distinct items and shop frequency while every saved purchase row counts", () => {
  const p = history([[1, ["milk", "leche"]], [8, ["leche", "bread"]]]);
  p.aliases.leche = "milk";
  p.purchases["1-0"].name = "Leche";
  const before = structuredClone(p), stats = shoppingStats(p, 100 * DAY);
  assert.deepEqual([stats.shopCount, stats.purchaseCount, stats.differentItems, stats.recentShopCount], [2, 4, 2, 2]);
  assert.deepEqual(stats.usuals[0], { key: "milk", name: "Leche", shopCount: 2, lastBoughtAt: 8 * DAY });
  assert.deepEqual(p, before);
  p.aliases["gtin:4006381333931"] = "milk";
  p.purchases["1-0"].gtin = "4006381333931";
  p.purchases["1-0"].key = "other";
  assert.equal(shoppingStats(p, 100 * DAY).differentItems, 2);
});
test("future and orphan rows do not affect saved-history facts", () => {
  const p = history([[1, ["milk", "bread"]], [200, ["eggs"]]]);
  p.purchases["0-1"].boughtAt = 101 * DAY;
  p.purchases.orphan = { tripId: "missing", key: "soap", name: "Soap", boughtAt: DAY };
  const stats = shoppingStats(p, 100 * DAY);
  assert.deepEqual([stats.shopCount, stats.purchaseCount, stats.differentItems], [1, 1, 1]);
  assert.equal(stats.usuals[0].key, "milk");
});
test("recent usuals use eight shops and five stable entries while totals retain old shops", () => {
  const p = history([[0, ["old"]], [1, []], ...Array.from({ length: 8 }, (_, i) => [i + 2, ["milk", "bread", "tea", "soap", "eggs", "apples"]])]);
  const stats = shoppingStats(p, 100 * DAY);
  assert.deepEqual([stats.shopCount, stats.purchaseCount, stats.differentItems, stats.recentShopCount], [10, 49, 7, 8]);
  assert.deepEqual(stats.usuals.map(item => [item.key, item.shopCount]), [["apples", 8], ["bread", 8], ["eggs", 8], ["milk", 8], ["soap", 8]]);
});
test("review retains frequency as primary and uses recent distinct-shop coverage only to break ties", () => {
  const tied = history([[1, ["eggs", "milk"]], [8, ["eggs"]], [15, ["milk"]]]);
  assert.deepEqual(reviewCandidates(tied, 30 * DAY).map(item => item.key), ["milk", "eggs"]);
  const dominant = history([[1, ["old", "old"]], [8, ["old"]], [15, ["old"]], [22, ["new"]], [29, ["new"]]]);
  assert.deepEqual(reviewCandidates(dominant, 40 * DAY).map(item => item.key), ["old", "new"]);
  assert.deepEqual(reviewCandidates(history([[1, ["a", "b"]], [8, ["a", "b"]]]), 20 * DAY).map(item => item.key), ["a", "b"]);
  assert.deepEqual(reviewCandidates(history([[1, ["a"]], [8, ["b"]], [15, ["b"]], [22, ["a"]]]), 40 * DAY).map(item => item.key), ["b", "a"]);
});

function el(tag, attributes = {}, ...children) {
  return { tag, ...attributes, children: children.flat(Infinity).filter(node => node != null),
    get textContent() { return this.children.map(node => typeof node === "object" ? node.textContent : node).join(""); },
    // The DOM converts null arguments to text; preserve that behavior so callers must filter them.
    replaceChildren(...nodes) { this.children = nodes.map(node => node && typeof node === "object" ? node : String(node)); } };
}
test("the stats disclosure is collapsed, keeps its open state on redraw and clears an unavailable list", () => {
  const section = shoppingStatsSection({ el, t, locale: "en" });
  section.update(projection(), 100 * DAY);
  assert.equal(section.element.open, undefined);
  assert.match(section.element.textContent, /Saved shops: 0/);
  assert.match(section.element.textContent, /Finish or import a shop/);
  assert.doesNotMatch(section.element.textContent, /null|undefined/);
  section.element.open = true;
  const p = history([[1, ["Milk"]], [8, ["Milk"]]]), before = structuredClone(p);
  section.update(p, 100 * DAY);
  assert.equal(section.element.open, true);
  assert.match(section.element.textContent, /Items recorded: 2/);
  assert.match(section.element.textContent, /In 2 of 2 shops/);
  assert.doesNotMatch(section.element.textContent, /null|undefined/);
  section.update(p, 100 * DAY);
  assert.equal(section.element.open, true);
  assert.deepEqual(p, before);
  section.update(projection(), 100 * DAY);
  assert.doesNotMatch(section.element.textContent, /Milk/);
  assert.match(section.element.textContent, /Saved shops: 0/);
});
