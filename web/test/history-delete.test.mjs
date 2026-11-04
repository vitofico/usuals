import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { deleteShopDialog } from "../src/history-delete.mjs";
const dictionary = JSON.parse(readFileSync(new URL("../locales/en.json", import.meta.url)));
const t = (key, values = {}) => dictionary[key]?.replace(/\{([a-z]+)\}/g, (_, name) => values[name]) ?? key;
function el(tag, attributes = {}, ...children) {
  return { tag, ...attributes, children: children.flat(Infinity).filter(child => child != null),
    get textContent() { return this.children.map(child => typeof child === "object" ? child.textContent : child).join(""); },
    set textContent(value) { this.children = [value]; } };
}
function fixture() {
  let root, cleanup, refresh, escape, calls = 0, backs = 0, closed = false, present = true;
  let operation = async () => "deleted";
  const button = (text, onclick) => el("button", { onclick }, text);
  deleteShopDialog({ el, button, t, date: "Nov 4, 2026", exists: () => present,
    remove: async () => { calls++; return operation(); }, back: () => { backs++; cleanup(); },
    closeDialog: () => { closed = true; cleanup(); },
    openDialog: (content, stop, redraw, cancel) => { root = content; cleanup = stop; refresh = redraw; escape = cancel; } });
  const descendants = node => node.children.flatMap(child => typeof child === "object" ? [child, ...descendants(child)] : []);
  return { root, calls: () => calls, backs: () => backs, closed: () => closed,
    click: name => descendants(root).find(node => node.tag === "button" && node.textContent === name).onclick(),
    operation: fn => operation = fn, gone: () => present = false, refresh: () => refresh(), escape: () => escape(), cleanup: () => cleanup() };
}
test("opening and cancelling a dated shop confirmation never remove purchases", () => {
  const ui = fixture();
  assert.match(ui.root.textContent, /Delete shop from Nov 4, 2026/);
  assert.match(ui.root.textContent, /removed for everyone/);
  assert.equal(ui.calls(), 0);
  ui.click("Cancel"); assert.equal(ui.calls(), 0); assert.equal(ui.backs(), 1);
  const escape = fixture(); escape.escape(); assert.equal(escape.calls(), 0); assert.equal(escape.backs(), 1);
});
test("confirmation removes once and returns to history only after success", async () => {
  const ui = fixture(); let release;
  ui.operation(() => new Promise(resolve => release = resolve));
  const pending = ui.click("Delete shop");
  await ui.click("Delete shop"); ui.escape();
  assert.equal(ui.calls(), 1); assert.equal(ui.backs(), 0);
  release("deleted"); await pending; assert.equal(ui.backs(), 1);
});
test("failed and unsupported deletions preserve the confirmation for retry", async () => {
  for (const outcome of ["failed", "unsupported", "exception"]) {
    const ui = fixture();
    ui.operation(async () => { if (outcome === "exception") throw Error("offline"); return outcome; });
    await ui.click("Delete shop"); assert.equal(ui.backs(), 0);
    assert.match(ui.root.textContent, outcome === "unsupported" ? /Update your Usuals server/ : /Could not delete this shop/);
    ui.operation(async () => "deleted"); await ui.click("Delete shop"); assert.equal(ui.backs(), 1);
  }
});
test("a removed shop or lost membership closes without a stale write", async () => {
  const ui = fixture(); ui.gone(); await ui.click("Delete shop");
  assert.equal(ui.calls(), 0); assert.equal(ui.closed(), true);
  const refreshed = fixture(); refreshed.gone(); refreshed.refresh(); assert.equal(refreshed.closed(), true);
});
test("a late completion after leaving the dialog does not reopen the old history", async () => {
  const ui = fixture(); let release;
  ui.operation(() => new Promise(resolve => release = resolve));
  const pending = ui.click("Delete shop"); ui.cleanup(); release("deleted"); await pending;
  assert.equal(ui.backs(), 0);
});
