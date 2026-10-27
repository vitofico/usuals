import test from "node:test";
import assert from "node:assert/strict";
import { categoryClassifier, groupByCategory } from "../src/categories.mjs";

const classify = categoryClassifier([
  { "category.PRODUCE": "peas,banana", "category.FROZEN": "frozen peas", "category.DAIRY": "milk,oat milk" },
  { "category.PRODUCE": "tomate", "category.DRINKS": "oat milk", "category.PANTRY": "salsa de tomate" },
]);

test("the longest matching phrase decides, as on Android", () => {
  assert.equal(classify("Frozen peas"), "FROZEN");
  assert.equal(classify("peas"), "PRODUCE");
  assert.equal(classify("Salsa de tomate"), "PANTRY");
});

test("names fold like item identities and match whole words", () => {
  assert.equal(classify("2 Bananas!"), "PRODUCE");
  assert.equal(classify("Peasant bread"), "OTHER");
});

test("equal phrases go to the earlier category and a shared override wins", () => {
  assert.equal(classify("oat milk"), "DAIRY");
  assert.equal(classify("oat milk", "DRINKS"), "DRINKS");
  assert.equal(classify("oat milk", "NOT_A_CATEGORY"), "DAIRY");
});

test("missing seeds leave every item in Other", () => {
  assert.equal(categoryClassifier(null)("milk"), "OTHER");
});

test("groups follow aisle order and keep row order", () => {
  const rows = ["soap", "milk", "banana", "cream"];
  const kind = { soap: "OTHER", milk: "DAIRY", banana: "PRODUCE", cream: "DAIRY" };
  assert.deepEqual(groupByCategory(rows, (row) => kind[row]),
    [["PRODUCE", ["banana"]], ["DAIRY", ["milk", "cream"]], ["OTHER", ["soap"]]]);
});
