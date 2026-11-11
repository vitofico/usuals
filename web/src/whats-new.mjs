export const compactModeUpdate = "compact-list-mode";
export const latestUpdate = compactModeUpdate;

const seenKey = "usuals-whats-new-seen";
const compactKey = "usuals-compact-mode-discovered";

export class WhatsNewStatus {
  #storage;
  #lastSeen = null;
  #compactDiscovered = false;

  constructor(storage) {
    this.#storage = storage;
  }

  #read(key) {
    try { return this.#storage?.getItem(key); } catch { return null; }
  }

  #write(key, value) {
    try { this.#storage?.setItem(key, value); } catch { /* Keep the choice for this session. */ }
  }

  isUnread(update) {
    return this.#lastSeen !== update && this.#read(seenKey) !== update;
  }

  markSeen(update) {
    this.#lastSeen = update;
    this.#write(seenKey, update);
  }

  get compactModeDiscovered() {
    return this.#compactDiscovered || this.#read(compactKey) === "true";
  }

  markCompactModeDiscovered() {
    this.#compactDiscovered = true;
    this.#write(compactKey, "true");
  }
}
