const DAY = 24 * 60 * 60 * 1000;
const defaults = () => ({ enabled: false, itemsAdded: true, shopsFinished: true, since: 0 });
const valid = (value) => value && ["enabled", "itemsAdded", "shopsFinished"].every((key) => typeof value[key] === "boolean")
  && Number.isSafeInteger(value.since) && value.since >= 0;

/** Personal choices for this account in this browser. Failed reads never opt someone in. */
export class NotificationChoices {
  constructor(storage, account, clock = Date.now) {
    Object.assign(this, { storage, clock });
    this.key = `usuals-notifications-v1:${account}`;
  }
  read() {
    try {
      const value = JSON.parse(this.storage?.getItem(this.key) ?? "null");
      return valid(value) ? value : defaults();
    } catch { return defaults(); }
  }
  configure(update) {
    const value = { ...this.read(), ...update, since: this.clock() };
    if (!valid(value)) throw new TypeError("invalid notification choices");
    try {
      if (!this.storage) throw new Error();
      this.storage.setItem(this.key, JSON.stringify(value));
    } catch { throw Object.assign(new Error(), { code: "notificationStorageUnavailable" }); }
    return value;
  }
}

/** Consumes every snapshot, including muted activity, so enabling never replays a backlog. */
export class BrowserActivity {
  #seen = new Map();
  #resetting = false;
  constructor(choices, clock = Date.now) { Object.assign(this, { choices, clock }); }
  consume(page, { lists, live = false, selected = null, visible = true } = {}) {
    if (page.reset) { this.#seen.clear(); this.#resetting = true; }
    for (const entry of page.removed) this.#seen.delete(entry.id);
    const settings = this.choices.read(), now = this.clock(), result = { items: 0, shops: 0 };
    for (const list of page.lists) {
      if (lists.get(list.id)?.seq !== list.seq) continue;
      const before = this.#seen.get(list.id);
      if (before && before.seq >= list.seq) continue;
      const events = Array.isArray(list.activity) ? list.activity.slice(-64) : [];
      this.#seen.set(list.id, { seq: list.seq, ids: new Set(events.map((event) => event.id)) });
      if (!before || this.#resetting || !live || !visible || !settings.enabled || list.id === selected || list.archived || list.members.length < 2) continue;
      for (const event of events) {
        if (typeof event.id !== "string" || before.ids.has(event.id) || !Number.isSafeInteger(event.at)
          || event.at <= settings.since || event.at < now - DAY || event.at > now + 5 * 60 * 1000
          || !Number.isSafeInteger(event.count) || event.count <= 0) continue;
        if (event.kind === "items_added" && settings.itemsAdded) result.items += event.count;
        if (event.kind === "shop_finished" && settings.shopsFinished) result.shops++;
      }
    }
    for (const entry of page.removed) if (!lists.has(entry.id)) this.#seen.delete(entry.id);
    if (!page.hasMore) this.#resetting = false;
    return result;
  }
}
