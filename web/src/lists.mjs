// The account's lists as the server last sent them (docs/sync/lists-v2.md). The browser keeps no copy of its own.
import { RunScope, request } from "./client.mjs";
import { intent } from "./intents.mjs";

const MiB = 1024 * 1024;
/** Feed entries carry whole lists of at most 7 MB each, so a page's bound follows from its size. */
const PAGE = 20, PAGE_MAX = PAGE * 8 * MiB, LIST_MAX = 8 * MiB;

export class ListStore {
  lists = new Map();
  cursor = null;
  #seqs = new Map();
  #returned = null;
  /** An entry wins only when it is newer than what the store holds, whether it came from the feed or a reply. */
  #take(id, seq, list) {
    if ((this.#seqs.get(id) ?? -Infinity) >= seq) return;
    this.#seqs.set(id, seq);
    if (list) this.lists.set(id, list);
    else this.lists.delete(id);
  }
  put(list) { this.#take(list.id, list.seq, list); }
  /** A list this account deleted or left: gone at once, and a feed page already in flight cannot bring it back. */
  drop(id) { this.lists.delete(id); }
  apply(page) {
    // A reset (first read, or a server restored from a backup) starts a fresh sequence, so earlier ones no longer compare.
    if (page.reset) { this.#seqs.clear(); this.#returned = new Set(); }
    const entries = [...page.lists.map((l) => [l.id, l.seq, l]), ...page.removed.map((r) => [r.id, r.seq, null])]
      .sort((a, b) => a[1] - b[1]);
    for (const [id, seq, list] of entries) {
      this.#take(id, seq, list);
      if (list) this.#returned?.add(id);
      else this.#returned?.delete(id);
    }
    this.cursor = page.cursor;
    if (this.#returned && !page.hasMore) {
      for (const id of [...this.lists.keys()]) if (!this.#returned.has(id)) this.lists.delete(id);
      this.#returned = null;
    }
  }
}

/**
 * Reads the feed from a reset, then waits on it while the tab is visible. A hidden tab drops its waiting request and
 * catches up when it is shown again; failures back off up to a minute.
 */
export class ListFeed {
  #generation = 0;
  #running = false;
  #scope = null;
  #wake = null;
  constructor({ store, transport = request, visible = () => !document.hidden, delay = (ms, wake) => {
    const timer = setTimeout(wake, ms);
    return () => clearTimeout(timer);
  }, onUpdate = () => {}, onStatus = () => {}, onSignedOut = () => {} }) {
    Object.assign(this, { store, transport, visible, delay, onUpdate, onStatus, onSignedOut });
  }
  start() {
    if (this.#running) return;
    this.#running = true;
    this.#loop(++this.#generation);
  }
  stop() {
    this.#running = false;
    this.#generation++;
    this.#scope?.stop();
    this.#wake?.();
  }
  /** For visibility and connectivity changes: a hidden tab stops waiting, a visible one retries now. */
  nudge() {
    if (!this.visible()) this.#scope?.stop();
    else this.#wake?.();
  }
  #pause(ms) {
    return new Promise((resolve) => {
      const cancel = ms === Infinity ? () => {} : this.delay(ms, resolve);
      this.#wake = () => { cancel(); resolve(); };
    }).finally(() => { this.#wake = null; });
  }
  async #loop(own) {
    let failures = 0, current = false;
    while (own === this.#generation) {
      if (!this.visible()) {
        current = false;
        await this.#pause(Infinity);
        continue;
      }
      const scope = this.#scope = new RunScope();
      const query = new URLSearchParams();
      if (this.store.cursor) query.set("cursor", this.store.cursor);
      query.set("limit", String(PAGE));
      query.set("wait", current ? "25" : "0");
      try {
        const page = await this.transport("/api/lists/changes?" + query, { scope, max: PAGE_MAX, timeoutMs: 35000 });
        if (own !== this.#generation) return;
        this.store.apply(page);
        failures = 0;
        current = !page.hasMore;
        this.onStatus(current ? "synced" : "syncing");
        this.onUpdate();
      } catch (error) {
        if (own !== this.#generation || !scope.valid) continue;
        if (error?.code === "unauthenticated") {
          this.stop();
          this.onSignedOut();
          return;
        }
        failures++;
        this.onStatus("offline");
        await this.#pause(Math.min(60000, 1000 * 2 ** Math.min(failures - 1, 6)));
      }
    }
  }
}

/** The list routes. Every reply that carries a list updates the store, so the screen shows what the server holds. */
export class ListsApi {
  constructor({ store, csrf, transport = request }) {
    Object.assign(this, { store, csrf, transport });
  }
  #call(path, options = {}) {
    return this.transport(path, options.method ? { csrf: this.csrf(), ...options } : options);
  }
  async create(name) {
    for (let attempt = 0; ; attempt++) {
      try {
        const list = await this.#call("/api/lists/" + crypto.randomUUID(),
          { method: "PUT", body: { projection: { name } }, status: [200, 201], max: LIST_MAX });
        this.store.put(list);
        return list;
      } catch (error) {
        // A taken id is never ours to reuse; a fresh one is.
        if (error?.code !== "list_exists" || attempt) throw error;
      }
    }
  }
  /** Sends intents in batches of at most 100 and returns every result with the list after the last batch. */
  async send(listId, intents) {
    const results = [];
    let list = null;
    for (let at = 0; at < intents.length; at += 100) {
      let reply;
      try {
        reply = await this.#call(`/api/lists/${listId}/intents`,
          { method: "POST", body: { intents: intents.slice(at, at + 100) }, max: LIST_MAX });
      } catch (error) {
        if (error?.code === "not_found") this.store.drop(listId);
        throw error;
      }
      this.store.put(reply.list);
      results.push(...reply.results);
      list = reply.list;
    }
    return { results, list };
  }
  apply(listId, kind, payload) {
    const sent = intent(listId, kind, payload);
    return this.send(listId, [sent]).then((reply) => ({ ...reply, intent: sent }));
  }
  async delete(listId) {
    await this.#call("/api/lists/" + listId, { method: "DELETE", status: 204 });
    this.store.drop(listId);
  }
  async leave(listId, userId) {
    await this.#call(`/api/lists/${listId}/members/${userId}`, { method: "DELETE", status: 204 });
    this.store.drop(listId);
  }
  async removeMember(listId, userId) {
    await this.#call(`/api/lists/${listId}/members/${userId}`, { method: "DELETE", status: 204 });
    // The feed brings the list's next version; until then the person is already gone from the dialog.
    const list = this.store.lists.get(listId);
    if (list) this.store.lists.set(listId, { ...list, members: list.members.filter((m) => m.id !== userId) });
  }
  async addMember(listId, userId) {
    const list = await this.#call(`/api/lists/${listId}/members`, { method: "POST", body: { userId }, max: LIST_MAX });
    this.store.put(list);
    return list;
  }
  /** Archives the list (true) or restores it (false): for everyone when the owner asks, otherwise for this account only. */
  async archive(listId, archived) {
    const list = await this.#call(`/api/lists/${listId}/archived`, { method: "PUT", body: { archived }, max: LIST_MAX });
    this.store.put(list);
    return list;
  }
  async people(query) {
    return (await this.#call("/api/people?" + new URLSearchParams({ q: query }))).people;
  }
}

/** Who has access, owner first, with the one action this account may take on each row. */
export function access(list, me) {
  const people = list.members.some((m) => m.id === list.owner.id) ? list.members
    : [{ id: list.owner.id, name: list.owner.name, role: "owner" }, ...list.members];
  return [...people].sort((a, b) => (a.role === "owner" ? -1 : b.role === "owner" ? 1 : 0)).map((member) => ({
    ...member,
    you: member.id === me,
    action: member.role === "owner" ? null : member.id === me ? "leave" : list.role === "owner" ? "remove" : null,
  }));
}

/**
 * The lists by name, split into the selector's and the archived ones, and the list to show: the selected one while it
 * is not archived, otherwise the first one left, or null when every list is archived.
 */
export function arrange(lists, selected, name, locale) {
  const sorted = [...lists].sort((a, b) => name(a).localeCompare(name(b), locale) || (a.id < b.id ? -1 : 1));
  const active = sorted.filter((l) => !l.archived), archived = sorted.filter((l) => l.archived);
  return { active, archived, selected: active.some((l) => l.id === selected) ? selected : active[0]?.id ?? null };
}

/** What an archived list offers: an editor's restore cannot undo the owner's archive, so they may only leave it. */
export const archivedAction = (list) => list.role === "editor" && list.archivedByOwner ? "leave" : "restore";

/** The first result the person should hear about, named as the projection records it. */
export function problem({ results, list }) {
  for (const { id, outcome } of results) {
    if (outcome === "conflict") return "conflict_" + (list.projection.conflicts?.[id]?.code ?? "stale");
    if (outcome === "rejected" || outcome === "upgrade_required") return outcome;
  }
  return null;
}
