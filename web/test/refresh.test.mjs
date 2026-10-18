import test from "node:test";
import assert from "node:assert/strict";
import { RefreshLoop } from "../src/refresh.mjs";
const tick = () => new Promise((resolve) => setImmediate(resolve));
test("timer and forced refreshes conflate into one active and one trailing call", async () => {
  let callback,
    finish,
    calls = 0,
    allowed = true;
  const loop = new RefreshLoop({
    canRun: () => allowed,
    refresh: async () => {
      calls++;
      if (calls === 1)
        await new Promise((resolve) => {
          finish = resolve;
        });
    },
    schedule: (fn, ms) => {
      assert.equal(ms, 20000);
      callback = fn;
      return 7;
    },
    cancel: () => {},
  });
  loop.start();
  callback();
  await tick();
  for (let i = 0; i < 100; i++) {
    callback();
    loop.run(true);
  }
  assert.equal(calls, 1);
  finish();
  await loop.pending;
  assert.equal(calls, 2);
  allowed = false;
  callback();
  await tick();
  assert.equal(calls, 2);
  loop.stop();
});
test("stopping a refresh invalidates its trailing request and clears its timer", async () => {
  let finish,
    calls = 0,
    cancelled = false;
  const loop = new RefreshLoop({
    canRun: () => true,
    refresh: async () => {
      calls++;
      await new Promise((resolve) => {
        finish = resolve;
      });
    },
    schedule: () => 9,
    cancel: (id) => {
      assert.equal(id, 9);
      cancelled = true;
    },
  });
  loop.start();
  const pending = loop.run();
  await tick();
  loop.run(true);
  loop.stop();
  finish();
  await pending;
  assert.equal(calls, 1);
  assert.equal(cancelled, true);
  assert.equal(loop.pending, null);
});

test("default timers retain the browser global receiver", () => {
  const originalSchedule = globalThis.setInterval,
    originalCancel = globalThis.clearInterval;
  let scheduled = false,
    cancelled = false;
  try {
    globalThis.setInterval = function (_callback, delay) {
      assert.equal(this, globalThis, "Window timer receiver");
      assert.equal(delay, 20000);
      scheduled = true;
      return 41;
    };
    globalThis.clearInterval = function (id) {
      assert.equal(this, globalThis, "Window timer receiver");
      assert.equal(id, 41);
      cancelled = true;
    };
    const loop = new RefreshLoop({
      refresh: async () => {},
      canRun: () => true,
    });
    loop.start();
    loop.stop();
    assert.equal(scheduled, true);
    assert.equal(cancelled, true);
  } finally {
    globalThis.setInterval = originalSchedule;
    globalThis.clearInterval = originalCancel;
  }
});
