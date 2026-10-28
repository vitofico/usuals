import test from "node:test";
import assert from "node:assert/strict";
import { BrowserActivity, NotificationChoices } from "../src/notifications.mjs";

const DAY = 24 * 60 * 60 * 1000;
const empty = { items: 0, shops: 0 };
const storage = () => {
  const values = new Map();
  return { getItem: (key) => values.get(key) ?? null, setItem: (key, value) => values.set(key, value) };
};
const event = (id, at, kind = "items_added", count = 1) => ({ id, at, kind, count });
const list = (seq, activity = [], extra = {}) => ({ id: "other", seq, activity, members: [{ id: "me" }, { id: "them" }], archived: false, ...extra });
const page = (lists, extra = {}) => ({ lists, removed: [], reset: false, hasMore: false, ...extra });
function harness() {
  let now = 2 * DAY;
  const choices = new NotificationChoices(storage(), "me", () => now), tracker = new BrowserActivity(choices, () => now);
  const consume = (entries, options = {}, extra = {}) => tracker.consume(page(entries, extra), { lists: new Map(entries.map((entry) => [entry.id, entry])), live: true, visible: true, selected: "current", ...options });
  return { choices, tracker, consume, advance: (ms = 1) => now += ms, now: () => now };
}

test("choices start off, are scoped per account and persist only after a successful write", () => {
  const saved = storage(), first = new NotificationChoices(saved, "first", () => 100);
  assert.equal(first.read().enabled, false);
  assert.equal(first.configure({ enabled: true }).since, 100);
  assert.equal(new NotificationChoices(saved, "first").read().enabled, true);
  assert.equal(new NotificationChoices(saved, "second").read().enabled, false);
  for (const unavailable of [null, { getItem: () => { throw new Error(); }, setItem: () => { throw new Error(); } }]) {
    const choices = new NotificationChoices(unavailable, "me", () => 100);
    assert.equal(choices.read().enabled, false);
    assert.throws(() => choices.configure({ enabled: true }), { code: "notificationStorageUnavailable" });
    assert.equal(choices.read().enabled, false);
  }
  saved.setItem(first.key, '{"enabled":true}');
  assert.equal(first.read().enabled, false, "partial preferences cannot enable notices");
});

test("first load, new lists and every page of a reset establish a silent baseline", () => {
  const h = harness(); h.choices.configure({ enabled: true }); h.advance();
  assert.deepEqual(h.consume([list(1, [event("a", h.now())])]), empty);
  assert.deepEqual(h.consume([list(2, [event("b", h.now())])], {}, { reset: true, hasMore: true }), empty);
  assert.deepEqual(h.consume([list(3, [event("c", h.now())])], {}, { hasMore: false }), empty);
  assert.deepEqual(h.consume([list(4, [event("d", h.now())])]), { items: 1, shops: 0 });
});

test("one feed update groups fresh activity and consumes it once", () => {
  const h = harness(); h.choices.configure({ enabled: true }); h.consume([list(1)]); h.advance();
  const entries = [list(2, [event("a", h.now(), "items_added", 3), event("b", h.now(), "shop_finished", 4)])];
  assert.deepEqual(h.consume(entries), { items: 3, shops: 1 });
  assert.deepEqual(h.consume(entries), empty);
  assert.deepEqual(h.consume([list(3, entries[0].activity)]), empty);
  assert.deepEqual(h.consume([list(2, [event("stale", h.now())])], { lists: new Map([["other", list(4)]]) }), empty, "stale page cannot alert over newer state");
});

test("disabled activity and events waiting before enable or a type change never replay", () => {
  const h = harness(); h.consume([list(1)]); h.advance();
  assert.deepEqual(h.consume([list(2, [event("muted", h.now())])]), empty);
  h.advance(); h.choices.configure({ enabled: true, itemsAdded: false }); h.advance();
  assert.deepEqual(h.consume([list(3, [event("before-enable", h.now() - 2), event("off", h.now()), event("shop", h.now(), "shop_finished")])]), { items: 0, shops: 1 });
  h.advance(); h.choices.configure({ itemsAdded: true, shopsFinished: false }); h.advance();
  assert.deepEqual(h.consume([list(4, [event("off", h.now() - 2), event("new", h.now()), event("shop2", h.now(), "shop_finished")])]), { items: 1, shops: 0 });
  h.choices.configure({ enabled: false }); h.advance();
  assert.deepEqual(h.consume([list(5, [event("all-off", h.now())])]), empty);
});

test("current, archived, unshared, hidden and catch-up activity stays silent and consumed", () => {
  for (const [extra, options] of [[{}, { selected: "other" }], [{ archived: true }, {}], [{ members: [{ id: "me" }] }, {}], [{}, { visible: false }], [{}, { live: false }]]) {
    const h = harness(); h.choices.configure({ enabled: true }); h.consume([list(1)]); h.advance();
    assert.deepEqual(h.consume([list(2, [event("quiet", h.now())], extra)], options), empty);
    assert.deepEqual(h.consume([list(3, [event("quiet", h.now())])]), empty);
  }
});

test("stale, future, malformed and unknown activity is ignored", () => {
  const h = harness(); h.choices.configure({ enabled: true }); h.consume([list(1)]); h.advance(DAY + 2);
  assert.deepEqual(h.consume([list(2, [event("old", h.now() - DAY - 1), event("future", h.now() + 5 * 60 * 1000 + 1), event("negative", h.now(), "items_added", -1), event("unknown", h.now(), "edited"), event("fractional", h.now(), "items_added", 1.5)])]), empty);
  const legacy = list(3); delete legacy.activity;
  assert.deepEqual(h.consume([legacy]), empty, "older servers may omit activity");
});

test("removal then re-sharing starts a new baseline, including a single page with both entries", () => {
  const h = harness(); h.choices.configure({ enabled: true }); h.consume([list(1)]); h.advance();
  assert.deepEqual(h.consume([list(3, [event("backfill", h.now())])], {}, { removed: [{ id: "other", seq: 2 }] }), empty);
  assert.deepEqual(h.consume([], {}, { removed: [{ id: "other", seq: 4 }] }), empty);
  assert.deepEqual(h.consume([list(5, [event("more-backfill", h.now())])]), empty);
});

test("small server clock skew does not permanently consume fresh activity", () => {
  const h = harness(); h.choices.configure({ enabled: true }); h.consume([list(1)]); h.advance();
  assert.deepEqual(h.consume([list(2, [event("server-ahead", h.now() + 4 * 60 * 1000)])]), { items: 1, shops: 0 });
  assert.deepEqual(h.consume([list(3, [event("server-ahead", h.now() + 4 * 60 * 1000)])]), empty);
});
