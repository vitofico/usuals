import test from "node:test";
import assert from "node:assert/strict";
import { DialogFocus, DialogCancellation } from "../src/dialog-focus.mjs";

function control(overrides = {}) {
  return { isConnected: true, disabled: false, hidden: false, calls: [],
    focus(options) { this.calls.push(options); }, ...overrides };
}

test("closing a nested preferences dialog returns to the original page trigger", () => {
  const focus = new DialogFocus(), preferences = control(), appearance = control();
  focus.remember({ nested: false, activeElement: preferences });
  focus.remember({ nested: true, activeElement: appearance });
  focus.restore(control());
  assert.deepEqual(preferences.calls, [{ preventScroll: true }]);
  assert.deepEqual(appearance.calls, []);
  assert.equal(focus.opener, null);
});

test("a feed refresh removing the trigger returns focus to the live capture field", () => {
  const focus = new DialogFocus(), trigger = control(), capture = control();
  focus.remember({ nested: false, activeElement: trigger });
  trigger.isConnected = false;
  focus.restore(capture);
  assert.deepEqual(trigger.calls, []);
  assert.deepEqual(capture.calls, [{ preventScroll: true }]);
});

test("disabled or hidden triggers use the fallback without moving focus to unavailable controls", () => {
  for (const unavailable of [{ disabled: true }, { hidden: true }]) {
    const focus = new DialogFocus(), trigger = control(unavailable), fallback = control();
    focus.remember({ nested: false, activeElement: trigger });
    focus.restore(fallback);
    assert.equal(trigger.calls.length, 0);
    assert.equal(fallback.calls.length, 1);
  }
  const focus = new DialogFocus();
  assert.doesNotThrow(() => focus.restore(null));
});


test("Escape from a remove confirmation returns the same editor and drafts, then clears its handler", () => {
  const cancel = new DialogCancellation();
  const editor = { name: "Oat milk", note: "Unsaved draft", open: false };
  let closed = 0;
  cancel.set(() => { editor.open = true; });
  cancel.invoke(() => { closed++; });
  assert.deepEqual(editor, { name: "Oat milk", note: "Unsaved draft", open: true });
  assert.equal(closed, 0);
  cancel.invoke(() => { editor.open = false; closed++; });
  assert.equal(editor.open, false);
  assert.equal(closed, 1);
});

test("closing or replacing a dialog cannot leak its cancellation action", () => {
  const cancel = new DialogCancellation();
  let stale = 0, current = 0, fallback = 0;
  cancel.set(() => { stale++; });
  cancel.clear();
  cancel.invoke(() => { fallback++; });
  cancel.set(() => {
    current++;
    cancel.set(() => { current++; });
  });
  cancel.invoke(() => { fallback++; });
  cancel.invoke(() => { fallback++; });
  assert.equal(stale, 0);
  assert.equal(current, 2);
  assert.equal(fallback, 1);
});
