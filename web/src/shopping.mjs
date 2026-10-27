import { canonical, rows, captures, isChecked } from "./intents.mjs";
import { normalize } from "./identity.mjs";

export const DAY = 86400000;
const compare = (a, b) => a < b ? -1 : a > b ? 1 : 0;
export function itemKey(item, aliases = {}) {
  const barcode = item.gtin && `gtin:${item.gtin}`;
  return canonical(barcode && (item.name === item.gtin || Object.hasOwn(aliases, barcode))
    ? barcode : item.key ?? normalize(item.name), aliases);
}
export function purchases(p) {
  return Object.entries(p.purchases ?? {}).map(([id, value]) => ({ ...value, id }));
}
export function historyTrips(p, query = "") {
  const needle = normalize(query);
  const grouped = new Map();
  for (const purchase of purchases(p)) {
    if (needle && !normalize(purchase.name).includes(needle)) continue;
    const group = grouped.get(purchase.tripId) ?? [];
    group.push(purchase);
    grouped.set(purchase.tripId, group);
  }
  return [...grouped].map(([id, items]) => ({ id,
    closedAt: p.trips?.[id]?.closedAt ?? Math.max(...items.map((item) => item.boughtAt)),
    items: items.sort((a, b) => compare(a.name, b.name) || compare(a.id, b.id)),
  })).sort((a, b) => b.closedAt - a.closedAt || compare(a.id, b.id));
}
/** Same factual eligibility and ordering as smart/ReviewRanker.kt. No inferred intervals. */
export function reviewCandidates(p, now, muted = []) {
  const trips = Object.entries(p.trips ?? {}).map(([id, trip]) => ({ ...trip, id }))
    .filter((trip) => trip.closedAt <= now)
    .sort((a, b) => b.closedAt - a.closedAt || compare(a.id, b.id)).slice(0, 8);
  if (!trips.length) return [];
  const ids = new Set(trips.map((trip) => trip.id)), aliases = p.aliases ?? {};
  const excluded = new Set([...rows(p).map((row) => itemKey(row.facts, aliases)), ...muted.map((key) => canonical(key, aliases))]);
  const preferences = new Map();
  // Prefer a canonical preference over an alias, just like ItemIdentity.preferences.
  for (const key of Object.keys(p.preferences ?? {}).sort((a, b) =>
    Number(a !== canonical(a, aliases)) - Number(b !== canonical(b, aliases)) || compare(a, b))) {
    const resolved = canonical(key, aliases);
    if (!preferences.has(resolved)) preferences.set(resolved, p.preferences[key]);
  }
  const groups = new Map();
  for (const purchase of purchases(p).filter((item) => ids.has(item.tripId) && item.boughtAt <= now)) {
    const key = itemKey(purchase, aliases), group = groups.get(key) ?? [];
    group.push(purchase); groups.set(key, group);
  }
  return [...groups].flatMap(([key, items]) => {
    const preference = preferences.get(key), shopCount = new Set(items.map((item) => item.tripId)).size;
    if (!key.trim() || excluded.has(key) || preference?.muted || preference?.repeatEveryDays != null ||
        (preference?.snoozedUntil != null && trips[0].closedAt <= preference.snoozedUntil) ||
        (shopCount < 2 && (trips.length >= 3 || !items.some((item) => item.tripId === trips[0].id)))) return [];
    const latest = items.sort((a, b) => b.boughtAt - a.boughtAt || compare(a.id, b.id))[0];
    return latest.boughtAt > now - DAY ? [] : [{ ...latest, key, shopCount, windowSize: trips.length }];
  }).sort((a, b) => b.shopCount - a.shopCount || a.boughtAt - b.boughtAt || compare(a.key, b.key));
}
/** Reusing history/favourites leaves existing unchecked quantities alone, including aliases. */
export function addAgain(p, items, now) {
  const aliases = p.aliases ?? {}, present = new Set(rows(p).filter((row) => !isChecked(row)).map((row) => itemKey(row.facts, aliases)));
  const fresh = items.filter((item) => {
    const key = itemKey(item, aliases);
    if (present.has(key)) return false;
    present.add(key); return true;
  });
  return captures(p, fresh.map((item) => ({ ...item, key: itemKey(item, aliases) })), now).map((payload, index) => ({
    ...payload, store: fresh[index].store ?? null, gtin: fresh[index].gtin ?? null,
  }));
}
/** A replay Undo may only remove the exact row this session created. */
export function unchangedRow(p, saved) {
  const row = rows(p).find((item) => item.id === saved?.id);
  return row && row.lifecycle === saved.lifecycle &&
    JSON.stringify(row.revisions) === JSON.stringify(saved.revisions) &&
    JSON.stringify(row.facts) === JSON.stringify(saved.facts) ? row : null;
}
export function guardedRemoval(row, now) {
  const expected = Object.fromEntries(["name", "rawText", "quantity", "check", "note", "store", "position"]
    .map((field) => [field, row.revisions[field] ?? null]));
  return { target: row.id, expected: { ...expected, lifecycle: row.lifecycle ?? null }, deletedAt: now, updatedAt: now };
}

/** Personal device choices, scoped to this account and list. Never a replica or auth store. */
export class ShoppingChoices {
  constructor(storage, scope) { this.storage = storage; this.key = `usuals-shopping-v1:${scope}`; }
  read() {
    try {
      const value = JSON.parse(this.storage?.getItem(this.key) ?? "null");
      return { favorites: Array.isArray(value?.favorites) ? value.favorites.filter((item) =>
        typeof item?.name === "string" && item.name.trim() && item.name.length <= 1000 &&
        (item.quantity == null || typeof item.quantity === "string" && Number.isFinite(Number(item.quantity)) && Number(item.quantity) >= 0) &&
        (item.quantity == null || ["PIECE", "PACK", "GRAM", "KILOGRAM", "MILLILITRE", "LITRE"].includes(item.unit))).slice(0, 500) : [],
      muted: Array.isArray(value?.muted) ? value.muted.filter((key) => typeof key === "string").slice(0, 500) : [] };
    } catch { return { favorites: [], muted: [] }; }
  }
  write(update) {
    const value = update(this.read());
    try {
      if (!this.storage) throw new Error();
      this.storage.setItem(this.key, JSON.stringify(value));
    } catch { throw Object.assign(new Error(), { code: "shoppingStorageUnavailable" }); }
    return value;
  }
}
