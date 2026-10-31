import test from "node:test";
import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import { readFileSync } from "node:fs";
import { occasionPacksDialog } from "../src/occasion-packs-dialog.mjs";
import { ShoppingChoices } from "../src/shopping.mjs";
globalThis.crypto ??= webcrypto;
const dictionary = JSON.parse(readFileSync(new URL("../locales/en.json", import.meta.url)));
const t = (key, values = {}) => dictionary[key]?.replace(/\{([a-z]+)\}/g, (_, name) => values[name]) ?? key;
function el(tag, attributes = {}, ...children) {
  return { tag, ...attributes, children: children.flat(Infinity).filter(child => child != null),
    get textContent() { return this.children.map(child => typeof child === "string" ? child : child.textContent).join(""); },
    set textContent(value) { this.children = [String(value)]; },
    append(...items) { this.children.push(...items.flat(Infinity).filter(child => child != null)); },
    replaceChildren(...items) { this.children = []; this.append(...items); } };
}
const descendants = node => node.children.flatMap(child => typeof child === "object" ? [child, ...descendants(child)] : []);
const template = { id: "picnic", name: "Picnic", items: [{ id: "milk", name: "Milk", amount: 2, unit: "LITRE", note: "Cold", store: "Market", category: "DAIRY" }] };
function fixture(packs = []) {
  let root, cancel, closed = false, success = true;
  const data = new Map(), choices = new ShoppingChoices({ getItem: key => data.get(key) ?? null, setItem: (key, value) => data.set(key, value) }, "alice:list");
  choices.write(value => ({ ...value, packs: structuredClone(packs) }));
  const p = { rows: {}, aliases: {}, tombstones: {}, preferences: {} }, sent = [];
  const button = (name, onclick, className, attributes) => el("button", { onclick, ...attributes }, name);
  occasionPacksDialog({ el, button, t, choices, projection: () => p, act: fn => fn(),
    add: async items => { sent.push(structuredClone(items)); return success; },
    closeDialog: () => closed = true, openDialog: (content, cleanup, refresh, escape) => { root = content; cancel = escape; } });
  const find = predicate => descendants(root).find(predicate);
  return { p, choices, sent, find, root: () => root, cancel: () => cancel(), closed: () => closed,
    fail: () => success = false, succeed: () => success = true,
    click: text => { const node = find(node => node.tag === "button" && node.textContent === text); assert.ok(node, text); return node.onclick(); },
    input: (label, value) => { const input = find(node => node.tag === "label" && node.children[0] === t(label)).children.find(node => node.tag === "input"); input.value = value; input.oninput({ target: input }); } };
}
test("empty creation, item editing and deletion never change shopping rows", async () => {
  const ui = fixture();
  ui.click(t("pack_new")); ui.input("pack_name", "Visitors"); ui.click(t("pack_add_item"));
  ui.input("pack_item_name", "Bread"); ui.input("pack_quantity", "2"); ui.input("pack_note", "Sliced"); ui.input("pack_store", "Market");
  ui.click(t("pack_save")); await ui.click(t("pack_save"));
  const saved = ui.choices.read().packs[0]; assert.equal(saved.name, "Visitors"); assert.equal(saved.items[0].note, "Sliced");
  assert.deepEqual(ui.p.rows, {});
  await ui.click(t("pack_delete")); assert.deepEqual(ui.choices.read().packs, []); assert.deepEqual(ui.p.rows, {});
});
test("review edits affect this use only and a failed add retains the review for retry", async () => {
  const ui = fixture([template]);
  ui.click(t("pack_use")); ui.click(t("pack_quantity")); ui.input("pack_quantity", "5"); ui.click(t("pack_save"));
  ui.fail(); await ui.click(t("pack_add_selected"));
  assert.match(ui.root().textContent, /Your review is kept/);
  assert.equal(ui.sent[0][0].quantity, "5.0"); assert.deepEqual(ui.choices.read().packs, [template]);
  ui.succeed(); await ui.click(t("pack_add_selected")); assert.equal(ui.sent[1][0].quantity, "5.0");
  assert.deepEqual(ui.choices.read().packs, [template]);
});
test("checked current-list rows are labelled already on list and review dismissal writes nothing", () => {
  const ui = fixture([template]);
  ui.p.rows.milk = { id: "milk", facts: { name: "Milk", key: "milk", checked: true } };
  ui.click(t("pack_use"));
  assert.match(ui.root().textContent, /Already on this list/);
  assert.equal(ui.find(node => node.type === "checkbox").disabled, true);
  ui.cancel(); assert.equal(ui.closed(), true); assert.deepEqual(ui.sent, []); assert.deepEqual(ui.choices.read().packs, [template]);
});
