import { categories } from "./categories.mjs";
import { decimal } from "./intents.mjs";
export const units = ["PIECE", "PACK", "GRAM", "KILOGRAM", "MILLILITRE", "LITRE"];
function canonicalGtin(value) {
  if (typeof value !== "string" || !/^(?:\d{8}|\d{12}|\d{13})$/.test(value)) return null;
  const code = value.padStart(13, "0");
  return /^0+$/.test(code) || [...code].reduce((sum, digit, index) => sum + Number(digit) * (index % 2 ? 3 : 1), 0) % 10 ? null : code;
}
export function validatePacks(packs) {
  if (!Array.isArray(packs) || packs.length > 100 || packs.reduce((sum, pack) => sum + (pack?.items?.length ?? 0), 0) > 2000) throw new Error("invalid packs");
  const ids = new Set();
  for (const pack of packs) {
    if (typeof pack?.id !== "string" || !pack.id.trim() || pack.id.length > 100 || ids.has(pack.id) ||
        typeof pack.name !== "string" || !pack.name.trim() || pack.name.length > 100 || !Array.isArray(pack.items) || pack.items.length > 200) throw new Error("invalid pack");
    ids.add(pack.id); const rows = new Set();
    for (const item of pack.items) {
      if (typeof item?.id !== "string" || !item.id.trim() || item.id.length > 100 || rows.has(item.id) ||
          typeof item.name !== "string" || !/[\p{L}\p{N}]/u.test(item.name) || item.name.length > 500 ||
          ((item.amount == null) !== (item.unit == null)) || item.amount != null && (typeof item.amount !== "number" || !Number.isFinite(item.amount) || item.amount < 0 || !units.includes(item.unit)) ||
          item.note != null && (typeof item.note !== "string" || item.note.length > 1000) ||
          item.store != null && (typeof item.store !== "string" || item.store.length > 80) ||
          item.category != null && !categories.includes(item.category) ||
          item.gtin != null && !canonicalGtin(item.gtin)) throw new Error("invalid pack item");
      rows.add(item.id);
    }
  }
  return packs;
}
export function packFacts(item) {
  return { ...item, ...(item.gtin != null ? { gtin: canonicalGtin(item.gtin) } : {}), quantity: item.amount == null ? null : decimal(item.amount) };
}
export function packItem(facts, category = null) {
  return { id: crypto.randomUUID(), name: facts.name, amount: facts.quantity == null ? null : Number(facts.quantity),
    unit: facts.unit ?? null, note: facts.note ?? null, store: facts.store ?? null, gtin: facts.gtin ?? null, category };
}
export function mergePacks(current, incoming) {
  validatePacks(current); validatePacks(incoming);
  const existing = new Map(current.map(pack => [pack.id, pack]));
  for (const pack of incoming) {
    if (existing.has(pack.id) && JSON.stringify(existing.get(pack.id)) !== JSON.stringify(pack)) throw new Error("pack conflict");
    existing.set(pack.id, pack);
  }
  return validatePacks([...existing.values()]);
}
export function readPackArchive(text) {
  if (new TextEncoder().encode(text).length > 10 * 1024 * 1024) throw new Error("archive too large");
  const value = JSON.parse(text);
  return validatePacks(value.occasionPacks ?? value.personalShopping?.packs);
}
