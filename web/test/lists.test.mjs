import test from "node:test";
import assert from "node:assert/strict";
import { Problem } from "../src/wire.mjs";
import { ListStore, ListFeed, ListsApi, access, archivedAction, arrange, problem } from "../src/lists.mjs";
import { intent } from "../src/intents.mjs";

const tick = () => new Promise((resolve) => setImmediate(resolve));
const person = (id, name = id) => ({ id, name });
const shared = (id, seq, { name = id, role = "owner", owner = person("me"), members, rows = {}, archived = false, archivedByOwner = false } = {}) => ({
  id, seq, role, owner, members: members ?? [{ id: owner.id, name: owner.name, role: "owner" }],
  projection: { name, rows, tombstones: {}, aliases: {}, conflicts: {}, rejected: {}, purchases: {} },
  archived, archivedByOwner,
});
const page = (cursor, { lists = [], removed = [], reset = false, hasMore = false } = {}) => ({ cursor, reset, hasMore, lists, removed });

test("a reset reads every page, applies entries in seq order and keeps the higher seq of a re-added list", () => {
  const store = new ListStore();
  store.apply(page("1.4", { reset: true, hasMore: true, lists: [shared("b", 4, { name: "B" }), shared("a", 2, { name: "A" })] }));
  assert.deepEqual([...store.lists.keys()].sort(), ["a", "b"]);
  assert.equal(store.cursor, "1.4");
  // Removed at 6, shared again at 8: the later entry wins whatever order the page lists them in.
  store.apply(page("1.9", { lists: [shared("a", 8, { name: "A again" })], removed: [{ id: "a", seq: 6 }, { id: "b", seq: 9 }] }));
  assert.equal(store.lists.get("a").projection.name, "A again");
  assert.equal(store.lists.has("b"), false);
  assert.equal(store.cursor, "1.9");
  // An older entry never replaces a newer one.
  store.apply(page("1.10", { lists: [shared("a", 7, { name: "stale" })] }));
  assert.equal(store.lists.get("a").projection.name, "A again");
});

test("after the last page of a reset, lists the reset did not return are dropped", () => {
  const store = new ListStore();
  store.apply(page("1.30", { reset: true, lists: [shared("kept", 20), shared("lost", 30)] }));
  // A restored server starts a new epoch, with sequence numbers lower than the ones this tab saw.
  store.apply(page("2.3", { reset: true, hasMore: true, lists: [shared("kept", 3, { name: "restored" })] }));
  assert.equal(store.lists.has("lost"), true, "kept until the reset's last page");
  store.apply(page("2.5", { lists: [shared("new", 5)] }));
  assert.deepEqual([...store.lists.keys()].sort(), ["kept", "new"]);
  assert.equal(store.lists.get("kept").projection.name, "restored");
});

test("replies update the store, a dropped list stays gone and an older feed page cannot revive it", () => {
  const store = new ListStore();
  store.apply(page("1.5", { reset: true, lists: [shared("a", 5)] }));
  store.put(shared("a", 7, { name: "after my edit" }));
  store.apply(page("1.6", { lists: [shared("a", 6, { name: "older" })] }));
  assert.equal(store.lists.get("a").projection.name, "after my edit");
  store.drop("a");
  store.apply(page("1.7", { lists: [shared("a", 7)] }));
  assert.equal(store.lists.has("a"), false);
  store.apply(page("1.12", { lists: [shared("a", 12, { name: "shared again" })] }));
  assert.equal(store.lists.get("a").projection.name, "shared again");
});

function feedHarness(responses) {
  const calls = [], delays = [];
  let visible = true;
  const transport = (path, options) => {
    const call = { path, options, query: new URL(path, "https://usuals.example.org").searchParams };
    calls.push(call);
    const next = responses.shift();
    if (next === "hang") return new Promise((_resolve, reject) => options.scope.controller.signal.addEventListener("abort",
      () => reject(new Problem("locked")), { once: true }));
    return typeof next === "function" ? next(call) : next instanceof Error ? Promise.reject(next) : Promise.resolve(next);
  };
  const store = new ListStore(), events = [];
  const feed = new ListFeed({
    store, transport, visible: () => visible,
    delay: (ms, wake) => { delays.push({ ms, wake }); return () => {}; },
    onUpdate: () => events.push("update"), onStatus: (s) => events.push(s), onSignedOut: () => events.push("signed out"),
  });
  return { feed, store, calls, delays, events, setVisible: (value) => { visible = value; } };
}

test("the feed starts with a reset, pages without waiting, then waits 25 seconds with its cursor", async () => {
  const h = feedHarness([
    page("1.2", { reset: true, hasMore: true, lists: [shared("a", 2)] }),
    page("1.3", { lists: [shared("b", 3)] }),
    page("1.3"),
    "hang",
  ]);
  h.feed.start();
  for (let i = 0; i < 10 && h.calls.length < 4; i++) await tick();
  h.feed.stop();
  assert.equal(h.calls[0].query.has("cursor"), false);
  assert.deepEqual(h.calls.map((c) => [c.query.get("cursor"), c.query.get("wait")]), [[null, "0"], ["1.2", "0"], ["1.3", "25"], ["1.3", "25"]]);
  assert.ok(h.calls.every((c) => c.path.startsWith("/api/lists/changes?") && c.query.get("limit") === "20"));
  assert.ok(h.calls[2].options.timeoutMs > 25000);
  assert.deepEqual([...h.store.lists.keys()].sort(), ["a", "b"]);
  assert.deepEqual(h.events.slice(0, 2), ["syncing", "update"]);
  assert.equal(h.events[2], "synced");
});

test("a hidden tab drops its waiting request and catches up without waiting when shown again", async () => {
  const h = feedHarness([page("1.1", { reset: true }), "hang", page("1.4", { lists: [shared("c", 4)] }), "hang"]);
  h.feed.start();
  for (let i = 0; i < 10 && h.calls.length < 2; i++) await tick();
  assert.equal(h.calls[1].query.get("wait"), "25");
  h.setVisible(false);
  h.feed.nudge();
  for (let i = 0; i < 10; i++) await tick();
  assert.equal(h.calls[1].options.scope.valid, false, "the waiting request was aborted");
  assert.equal(h.calls.length, 2, "nothing is requested while hidden");
  h.setVisible(true);
  h.feed.nudge();
  for (let i = 0; i < 10 && h.calls.length < 4; i++) await tick();
  assert.deepEqual([h.calls[2].query.get("cursor"), h.calls[2].query.get("wait")], ["1.1", "0"]);
  assert.equal(h.store.lists.has("c"), true);
  assert.equal(h.calls[3].query.get("wait"), "25");
  h.feed.stop();
});

test("failures back off and reset after a success; a nudge retries at once; 401 signs out and stops", async () => {
  const h = feedHarness([new TypeError("offline"), new Problem("unavailable"), page("1.1", { reset: true }),
    new Problem("rate_limited"), new Problem("unauthenticated")]);
  h.feed.start();
  await tick();
  assert.deepEqual(h.delays.map((d) => d.ms), [1000]);
  assert.equal(h.events.at(-1), "offline");
  h.delays[0].wake();
  for (let i = 0; i < 5; i++) await tick();
  assert.deepEqual(h.delays.map((d) => d.ms), [1000, 2000]);
  h.feed.nudge();
  for (let i = 0; i < 5; i++) await tick();
  assert.deepEqual(h.delays.map((d) => d.ms), [1000, 2000, 1000], "the success reset the backoff");
  h.delays[2].wake();
  for (let i = 0; i < 5; i++) await tick();
  assert.equal(h.events.at(-1), "signed out");
  assert.equal(h.calls.length, 5);
  h.feed.nudge();
  for (let i = 0; i < 5; i++) await tick();
  assert.equal(h.calls.length, 5, "a signed-out feed stays stopped");
});

test("a stopped feed ignores its late reply and a restarted one runs alone", async () => {
  let release;
  const h = feedHarness([() => new Promise((resolve) => { release = resolve; }), page("1.1", { reset: true, lists: [shared("a", 1)] }), "hang"]);
  h.feed.start();
  await tick();
  h.feed.stop();
  h.feed.start();
  for (let i = 0; i < 10 && h.calls.length < 3; i++) await tick();
  release(page("9.9", { reset: true, lists: [shared("ghost", 9)] }));
  for (let i = 0; i < 5; i++) await tick();
  assert.deepEqual([...h.store.lists.keys()], ["a"]);
  assert.equal(h.calls.length, 3);
  h.feed.stop();
});

function apiHarness(reply) {
  const calls = [], store = new ListStore();
  const api = new ListsApi({ store, csrf: () => "csrf-token", transport: async (path, options) => {
    calls.push({ path, options });
    return typeof reply === "function" ? reply(path, options) : reply;
  } });
  return { api, store, calls };
}

test("intents go to the list's route with CSRF and the screen shows the list the server returns", async () => {
  const after = shared("L", 3, { rows: { r: { id: "r", facts: { name: "Milk" }, revisions: {} } } });
  const h = apiHarness({ results: [{ id: "i", outcome: "applied" }], list: after });
  const reply = await h.api.apply("L", "capture", { name: "Milk" });
  assert.equal(h.calls[0].path, "/api/lists/L/intents");
  assert.equal(h.calls[0].options.method, "POST");
  assert.equal(h.calls[0].options.csrf, "csrf-token");
  const [sent] = h.calls[0].options.body.intents;
  assert.deepEqual({ ...sent, id: undefined }, { id: undefined, listId: "L", kind: "capture", payload: { name: "Milk" }, schema: 1, normalizer: 1 });
  assert.match(sent.id, /^[0-9a-f-]{36}$/);
  assert.equal(reply.intent, sent);
  assert.equal(h.store.lists.get("L"), after);
});

test("more than 100 intents go in batches and a list the account lost is dropped", async () => {
  let batches = 0;
  const h = apiHarness((_path, options) => ({ results: options.body.intents.map((i) => ({ id: i.id, outcome: "applied" })), list: shared("L", ++batches) }));
  const intents = Array.from({ length: 150 }, () => intent("L", "capture", {}));
  const reply = await h.api.send("L", intents);
  assert.deepEqual(h.calls.map((c) => c.options.body.intents.length), [100, 50]);
  assert.equal(reply.results.length, 150);
  assert.equal(h.store.lists.get("L").seq, 2);
  const gone = apiHarness(() => { throw new Problem("not_found"); });
  gone.store.put(shared("L", 1));
  await assert.rejects(gone.api.apply("L", "check", {}), (e) => e.code === "not_found");
  assert.equal(gone.store.lists.has("L"), false);
});

test("a new list is created under a fresh id, and once more when the id is taken", async () => {
  const h = apiHarness((path, options) => {
    if (h.calls.length === 1) throw new Problem("list_exists");
    return shared(path.slice("/api/lists/".length), 1, { name: options.body.projection.name });
  });
  const list = await h.api.create("Groceries");
  assert.equal(h.calls.length, 2);
  assert.notEqual(h.calls[0].path, h.calls[1].path);
  assert.deepEqual(h.calls[1].options.body, { projection: { name: "Groceries" } });
  assert.equal(h.calls[1].options.method, "PUT");
  assert.deepEqual(h.calls[1].options.status, [200, 201]);
  assert.equal(h.store.lists.get(list.id).projection.name, "Groceries");
  const never = apiHarness(() => { throw new Problem("list_exists"); });
  await assert.rejects(never.api.create("Again"), (e) => e.code === "list_exists");
  assert.equal(never.calls.length, 2);
});

test("deleting, leaving, adding and removing people use their routes and update the store", async () => {
  const h = apiHarness((path, options) => options.method === "POST"
    ? shared("L", 4, { members: [{ id: "me", name: "Me", role: "owner" }, { id: "ana", name: "Ana", role: "editor" }] }) : null);
  h.store.put(shared("L", 3));
  await h.api.addMember("L", "ana");
  assert.deepEqual([h.calls[0].path, h.calls[0].options.method, h.calls[0].options.body], ["/api/lists/L/members", "POST", { userId: "ana" }]);
  assert.equal(h.store.lists.get("L").members.length, 2);
  await h.api.removeMember("L", "ana");
  assert.deepEqual([h.calls[1].path, h.calls[1].options.method, h.calls[1].options.status], ["/api/lists/L/members/ana", "DELETE", 204]);
  assert.deepEqual(h.store.lists.get("L").members.map((m) => m.id), ["me"]);
  await h.api.leave("L", "me");
  assert.deepEqual([h.calls[2].path, h.calls[2].options.method], ["/api/lists/L/members/me", "DELETE"]);
  assert.equal(h.store.lists.has("L"), false);
  h.store.put(shared("M", 1));
  await h.api.delete("M");
  assert.deepEqual([h.calls[3].path, h.calls[3].options.method, h.calls[3].options.status], ["/api/lists/M", "DELETE", 204]);
  assert.equal(h.store.lists.has("M"), false);
  assert.ok(h.calls.every((c) => c.options.csrf === "csrf-token"));
});

test("archiving and restoring send the flag to the list's route with CSRF and store the list the server returns", async () => {
  const h = apiHarness((_path, options) => shared("L", h.calls.length + 3, { archived: options.body.archived }));
  h.store.put(shared("L", 3));
  const archived = await h.api.archive("L", true);
  assert.deepEqual([h.calls[0].path, h.calls[0].options.method, h.calls[0].options.body, h.calls[0].options.csrf],
    ["/api/lists/L/archived", "PUT", { archived: true }, "csrf-token"]);
  assert.equal(h.store.lists.get("L"), archived);
  assert.equal(archived.archived, true);
  await h.api.archive("L", false);
  assert.deepEqual(h.calls[1].options.body, { archived: false });
  assert.equal(h.store.lists.get("L").archived, false);
});

test("people search sends the query and reads without CSRF", async () => {
  const h = apiHarness({ people: [{ id: "ana", name: "Ana", detail: "ana@usuals.example.org" }] });
  assert.equal((await h.api.people("")).length, 1);
  await h.api.people("an a&b");
  assert.deepEqual(h.calls.map((c) => c.path), ["/api/people?q=", "/api/people?q=an+a%26b"]);
  assert.ok(h.calls.every((c) => c.options.csrf === undefined && c.options.method === undefined));
});

test("the owner may remove editors, an editor may only leave, and nobody removes the owner", () => {
  const members = [{ id: "ed", name: "Ed", role: "editor" }, { id: "own", name: "Olga", role: "owner" }, { id: "eve", name: "Eve", role: "editor" }];
  const asOwner = access({ ...shared("L", 1, { owner: person("own", "Olga"), members }), role: "owner" }, "own");
  assert.deepEqual(asOwner.map((m) => [m.id, m.you, m.action]), [["own", true, null], ["ed", false, "remove"], ["eve", false, "remove"]]);
  const asEditor = access({ ...shared("L", 1, { owner: person("own", "Olga"), members }), role: "editor" }, "ed");
  assert.deepEqual(asEditor.map((m) => [m.id, m.you, m.action]), [["own", false, null], ["ed", true, "leave"], ["eve", false, null]]);
  // An owner missing from the member list still shows first.
  const ownerless = access({ ...shared("L", 1, { owner: person("own", "Olga"), members: [members[0]] }), role: "editor" }, "ed");
  assert.deepEqual(ownerless.map((m) => [m.id, m.role]), [["own", "owner"], ["ed", "editor"]]);
});

test("conflicts and rejections are reported in the projection's own terms", () => {
  const list = shared("L", 2);
  list.projection.conflicts = { c1: { code: "unit" }, c2: { code: "stale" } };
  assert.equal(problem({ results: [{ id: "a", outcome: "applied" }, { id: "m", outcome: "merged" }, { id: "n", outcome: "no_op" }], list }), null);
  assert.equal(problem({ results: [{ id: "c1", outcome: "conflict" }], list }), "conflict_unit");
  assert.equal(problem({ results: [{ id: "x", outcome: "conflict" }], list }), "conflict_stale");
  assert.equal(problem({ results: [{ id: "r", outcome: "rejected" }], list }), "rejected");
  assert.equal(problem({ results: [{ id: "u", outcome: "upgrade_required" }], list }), "upgrade_required");
});

test("archived lists leave the selector and the selection moves to the first list that is not archived", () => {
  const name = (l) => l.projection.name;
  const lists = [shared("c", 1, { name: "Chores" }), shared("b", 2, { name: "Bakery", archived: true }),
    shared("a", 3, { name: "Apples" }), shared("z", 4, { name: "Zoo", archived: true, archivedByOwner: true })];
  const kept = arrange(lists, "c", name, "en");
  assert.deepEqual(kept.active.map((l) => l.id), ["a", "c"]);
  assert.deepEqual(kept.archived.map((l) => l.id), ["b", "z"]);
  assert.equal(kept.selected, "c");
  // Archived here, on another device or by the owner: the next render shows the first list left.
  assert.equal(arrange(lists, "b", name, "en").selected, "a");
  assert.equal(arrange(lists, null, name, "en").selected, "a");
  assert.equal(arrange(lists, "gone", name, "en").selected, "a");
  const none = arrange(lists.filter((l) => l.archived), "b", name, "en");
  assert.deepEqual([none.active, none.selected], [[], null]);
  assert.deepEqual(none.archived.map((l) => l.id), ["b", "z"]);
});

test("only the owner restores a list the owner archived; an editor restores their own archive or leaves the owner's", () => {
  assert.equal(archivedAction(shared("L", 1, { role: "owner", archived: true, archivedByOwner: true })), "restore");
  assert.equal(archivedAction(shared("L", 1, { role: "editor", archived: true })), "restore");
  assert.equal(archivedAction(shared("L", 1, { role: "editor", archived: true, archivedByOwner: true })), "leave");
});
