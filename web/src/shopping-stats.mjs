import { itemKey, purchases } from "./shopping.mjs";

const compare = (a, b) => a < b ? -1 : a > b ? 1 : 0;

/** Same read-only, selected-list facts as smart/ShoppingStats.kt. */
export function shoppingStats(p, now) {
  const trips = Object.entries(p.trips ?? {}).map(([id, trip]) => ({ ...trip, id }))
    .filter(trip => trip.closedAt <= now).sort((a, b) => b.closedAt - a.closedAt || compare(a.id, b.id));
  const ids = new Set(trips.map(trip => trip.id)), recent = new Set(trips.slice(0, 8).map(trip => trip.id));
  const saved = purchases(p).filter(item => ids.has(item.tripId) && item.boughtAt <= now), groups = new Map();
  for (const item of saved) {
    const key = itemKey(item, p.aliases ?? {});
    if (!key.trim()) continue;
    const group = groups.get(key) ?? [];
    group.push(item); groups.set(key, group);
  }
  const usuals = [...groups].flatMap(([key, items]) => {
    const rows = items.filter(item => recent.has(item.tripId));
    const latest = rows.sort((a, b) => b.boughtAt - a.boughtAt || compare(a.id, b.id))[0];
    return latest ? [{ key, name: latest.name, shopCount: new Set(rows.map(item => item.tripId)).size, lastBoughtAt: latest.boughtAt }] : [];
  }).sort((a, b) => b.shopCount - a.shopCount || b.lastBoughtAt - a.lastBoughtAt || compare(a.key, b.key)).slice(0, 5);
  return { shopCount: trips.length, purchaseCount: saved.length, differentItems: groups.size, recentShopCount: recent.size, usuals };
}

export function shoppingStatsSection({ el, t, locale }) {
  const body = el("div", { class: "shopping-stats-body" });
  const element = el("details", { class: "shopping-stats", id: "shopping-stats" }, el("summary", {}, t("stats_title")), body);
  const date = new Intl.DateTimeFormat(locale, { dateStyle: "medium" });
  return { element, update(p, now) {
    const stats = shoppingStats(p, now);
    body.replaceChildren(...[el("p", { class: "helper" }, t("stats_scope")),
      ...["shops", "purchases", "different"].map((key, index) => el("p", {}, t(`stats_${key}`, { count: [stats.shopCount, stats.purchaseCount, stats.differentItems][index] }))),
      stats.shopCount ? null : el("p", { class: "helper" }, t("stats_empty")),
      stats.usuals.length ? el("h3", {}, t("stats_usuals")) : null,
      stats.usuals.length ? el("p", { class: "helper" }, t("stats_window", { count: stats.recentShopCount })) : null,
      ...stats.usuals.map(item => el("section", { class: "shopping-usual" }, el("h4", {}, item.name),
        el("p", {}, t("stats_frequency", { count: item.shopCount, total: stats.recentShopCount })),
        el("p", { class: "helper" }, t("stats_last_bought", { date: date.format(item.lastBoughtAt) }))))].filter(node => node != null));
  } };
}
