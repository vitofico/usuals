// Mirrors :smart CategoryClassifier over the same input-language seeds, which the server build stages as category-seeds.json.
import { normalize } from "./identity.mjs";

export const categories = ["PRODUCE", "DAIRY", "BAKERY", "MEAT", "FISH", "FROZEN", "PANTRY", "DRINKS", "SNACKS",
  "HOUSEHOLD", "PERSONAL_CARE", "OTHER"];

/** Longer phrases win over the words inside them, so "frozen peas" is Frozen even though "peas" is Produce. */
export function categoryClassifier(seeds) {
  const unique = new Map();
  for (const category of categories)
    for (const word of seeds?.[category] ?? []) {
      const key = normalize(word);
      if (key && !unique.has(`${category} ${key}`)) unique.set(`${category} ${key}`, { key, category });
    }
  const words = (key) => key.split(" ").length;
  const order = [...unique.values()].sort((a, b) => words(b.key) - words(a.key) || b.key.length - a.key.length ||
    categories.indexOf(a.category) - categories.indexOf(b.category) || (a.key < b.key ? -1 : a.key > b.key ? 1 : 0));
  return (name, override = null) => {
    if (categories.includes(override)) return override;
    const key = ` ${normalize(name)} `;
    return order.find((seed) => key.includes(` ${seed.key} `))?.category ?? "OTHER";
  };
}

/** Category groups in aisle order, each keeping the rows' own order. */
export function groupByCategory(rows, categoryOf) {
  const groups = new Map();
  for (const row of rows) {
    const category = categoryOf(row);
    if (!groups.has(category)) groups.set(category, []);
    groups.get(category).push(row);
  }
  return [...groups].sort(([a], [b]) => categories.indexOf(a) - categories.indexOf(b));
}
