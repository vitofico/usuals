import test from "node:test";
import assert from "node:assert/strict";
import { WhatsNewStatus, latestUpdate } from "../src/whats-new.mjs";

test("opening the feed clears its dot until the next update and leaves discovery separate", () => {
  const entries = new Map();
  const storage = { getItem: (key) => entries.get(key), setItem: (key, value) => entries.set(key, value) };
  const status = new WhatsNewStatus(storage);
  assert.equal(status.isUnread(latestUpdate), true);
  status.markSeen(latestUpdate);
  const reopened = new WhatsNewStatus(storage);
  assert.equal(reopened.isUnread(latestUpdate), false);
  assert.equal(reopened.isUnread("next-update"), true);
  assert.equal(reopened.compactModeDiscovered, false);

  reopened.markCompactModeDiscovered();
  assert.equal(new WhatsNewStatus(storage).compactModeDiscovered, true);
  assert.equal(status.isUnread("next-update"), true);
});

test("a blocked storage write still clears the indicator for the open tab", () => {
  const storage = { getItem: () => "previous-update", setItem() { throw new Error("blocked"); } };
  const status = new WhatsNewStatus(storage);
  status.markSeen(latestUpdate);
  assert.equal(status.isUnread(latestUpdate), false);
});
