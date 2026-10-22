import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { importDialog } from "../src/import-dialog.mjs";

const dictionary = JSON.parse(readFileSync(new URL("../locales/en.json", import.meta.url), "utf8"));
const packFolder = new URL("../../core/parse/src/main/resources/input-language/", import.meta.url);
const packs = readdirSync(packFolder).filter(name => name.endsWith(".properties")).map(name =>
  Object.fromEntries(readFileSync(new URL(name, packFolder), "utf8").split("\n")
    .filter(line => line.includes("=") && !line.startsWith("#"))
    .map(line => [line.slice(0, line.indexOf("=")), line.slice(line.indexOf("=") + 1)])));
const t = (key, values = {}) => dictionary[key]?.replace(/\{([a-z]+)\}/g, (_, name) => values[name]) ?? key;

// A small element fixture exercises the real dialog callbacks without a browser or transport.
function el(tag, attributes = {}, ...children) {
  return {
    tag, value: "", ...attributes, children: children.flat(Infinity).filter(child => child != null), listeners: new Map(),
    get textContent() { return this.children.map(child => typeof child === "string" ? child : child.textContent).join(""); },
    set textContent(value) { this.children = [String(value)]; },
    append(...items) { this.children.push(...items.flat(Infinity).filter(child => child != null)); },
    replaceChildren(...items) { this.children = []; this.append(...items); },
    setAttribute(name, value) { this[name] = value; },
    removeAttribute(name) { delete this[name]; },
    addEventListener(name, callback) { this.listeners.set(name, callback); },
    removeEventListener(name) { this.listeners.delete(name); },
    focus() {},
    querySelectorAll(selector) {
      const selectors = selector.split(", ");
      return descendants(this).filter(node => selectors.some(part => part === node.tag ||
        part === "input[data-existing]" && node.tag === "input" && node["data-existing"]));
    },
  };
}
function descendants(node) {
  return node.children.flatMap(child => typeof child === "object" ? [child, ...descendants(child)] : []);
}
function fixture(server = null) {
  let root, cleanup;
  const sent = [], dialog = el("dialog");
  const button = (name, onclick) => el("button", { onclick }, name);
  importDialog({ el, button, t, dialog, packs, origins: [], server,
    projection: () => ({ rows: {}, tombstones: {}, aliases: {} }),
    valid: () => true, capture: async items => { sent.push(items); }, finished: async () => {},
    openDialog: (content, dispose) => { root = content; cleanup = dispose; }, closeDialog: () => cleanup(),
  });
  const find = predicate => descendants(root).find(predicate);
  const review = () => find(node => node.tag === "section" && node.children[0]?.tag === "h3");
  return { sent, find, review, close: () => cleanup(),
    click: text => find(node => node.tag === "button" && node.textContent === text).onclick(),
    source: text => find(node => node.class === "source-choices").children.find(node => node.textContent === text).onclick(),
  };
}

test("local review explains confirmation, omits duplicate labels, and preserves source facts when adding", async () => {
  const ui = fixture();
  ui.find(node => node.id === "import-markdown").value = "Carrots\n  Apples  \n2 kg potatoes, washed";
  await ui.click(t("importReview"));
  assert.equal(ui.review().hidden, false);
  assert.equal(ui.review().children[1].textContent, t("importMarkdownReviewHelp"));
  assert.doesNotMatch(ui.review().textContent, /Model results can be wrong/);
  const rows = ui.find(node => node.class === "import-review").children;
  assert.deepEqual(rows.map(row => descendants(row).filter(node => node.class === "helper").map(node => node.textContent)),
    [[], [], [t("quantityLabel", { amount: 2, unit: t("KILOGRAM") }), "2 kg potatoes, washed"]]);
  await ui.click(t("importAdd", { count: 3 }));
  assert.deepEqual(ui.sent[0].map(({ name, rawText, note, quantity, unit }) => ({ name, rawText, note, quantity, unit })), [
    { name: "Carrots", rawText: "Carrots", note: null, quantity: null, unit: null },
    { name: "Apples", rawText: "Apples", note: null, quantity: null, unit: null },
    { name: "potatoes", rawText: "2 kg potatoes, washed", note: "2 kg potatoes, washed", quantity: "2.0", unit: "KILOGRAM" },
  ]);
});

test("photo and server-text modes retain the model warning after a local review", async () => {
  const previous = globalThis.fetch, sent = [];
  globalThis.fetch = async (path, options) => {
    sent.push({ path, options });
    return Response.json(path === "/v1/ai/settings" ?
      { selected: "synthetic", revision: "synthetic-revision", dailyLimit: 10, used: 0, profiles: [] } :
      { items: [{ name: "Carrots", amount: 2, unit: "PIECE" }] });
  };
  let ui;
  try {
    ui = fixture({ csrf: "synthetic-csrf" });
    await new Promise(resolve => setImmediate(resolve));
    ui.find(node => node.id === "import-markdown").value = "Carrots";
    await ui.click(t("importReview"));
    await ui.click(t("importBack"));
    ui.source(t("importPhoto"));
    assert.equal(ui.review().children[1].textContent, t("importReviewHelp"));
    ui.source(t("importRecipeText"));
    const input = ui.find(node => node.tag === "textarea" && node["aria-label"] === t("importRecipeText"));
    input.value = "Recipe ingredients: two carrots";
    input.listeners.get("input")();
    const pane = ui.find(node => node.tag === "section" && node.children.includes(input));
    await pane.children.find(node => node.tag === "button").onclick();
    assert.equal(ui.review().hidden, false);
    assert.equal(ui.review().children[1].textContent, t("importReviewHelp"));
    assert.match(ui.review().textContent, /Model results can be wrong/);
    assert.equal(sent.length, 2);
    assert.equal(sent[1].path, "/v1/ai/text");
    assert.equal(JSON.parse(sent[1].options.body).text, input.value);
    const row = ui.find(node => node.class === "import-review").children[0];
    assert.deepEqual(descendants(row).filter(node => node.class === "helper").map(node => node.textContent),
      [t("quantityLabel", { amount: 2, unit: t("PIECE") })]);
  } finally { ui?.close(); globalThis.fetch = previous; }
});
