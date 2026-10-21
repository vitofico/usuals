// Intent payloads for the server's reducer (sync/.../Reducer.kt), which is the authority: it validates and applies them.
import { normalize } from "./identity.mjs";

export function canonical(key, aliases) {
  let value = key;
  const visited = new Set();
  while (Object.hasOwn(aliases, value) && !visited.has(value)) {
    visited.add(value);
    value = aliases[value];
  }
  return value;
}
/** Java's Double.toString, which the reducer requires for quantities. */
export function decimal(value) {
  if (value === 0) return "0.0";
  const a = Math.abs(value), sign = value < 0 ? "-" : "";
  if (a >= 1e7 || a < 1e-3) {
    let [mantissa, power] = a.toExponential().split("e");
    if (!mantissa.includes(".")) mantissa += ".0";
    return sign + mantissa + "E" + Number(power);
  }
  const s = a.toString();
  return sign + s + (s.includes(".") ? "" : ".0");
}
const time = (value) => (typeof value === "number" ? value : 0);
/** The items still on the list, in list order. */
export function rows(p) {
  return Object.values(p.rows)
    .filter((r) => !r.archived && !p.tombstones[r.id])
    .sort((a, b) => time(a.facts.position) - time(b.facts.position) ||
      time(a.facts.createdAt) - time(b.facts.createdAt) || (a.id < b.id ? -1 : 1));
}
export const isChecked = (row) => row.facts.checked === true;
export function intent(listId, kind, payload) {
  return { id: crypto.randomUUID(), listId, kind, payload, schema: 1, normalizer: 1 };
}
/** New items, each with the canonical key that the list's aliases give its name. */
export function captures(p, items, now) {
  const start = Math.max(-1, ...rows(p).map((r) => time(r.facts.position))) + 1;
  return items.map((item, index) => ({
    proposedId: crypto.randomUUID(),
    key: item.key ?? canonical(normalize(item.name), p.aliases),
    name: item.name,
    rawText: item.rawText ?? item.name,
    note: item.note ?? null,
    quantity: item.quantity ?? null,
    unit: item.quantity == null ? null : item.unit,
    normalizerVersion: 1,
    createdAt: now,
    updatedAt: now,
    position: start + index,
  }));
}
export function check(row, checked, now) {
  return {
    target: row.id,
    expected: { check: row.revisions.check ?? null },
    checked,
    checkedAt: checked ? now : null,
    updatedAt: now,
  };
}
const revisionField = (field) =>
  ["name", "key", "gtin"].includes(field) ? "name" : ["quantity", "unit"].includes(field) ? "quantity" : field;
/** Each changed field is guarded by the revision this browser last saw, so a concurrent edit becomes a conflict. */
export function edit(row, changes, now) {
  const expected = {};
  for (const field of Object.keys(changes)) expected[revisionField(field)] = row.revisions[revisionField(field)] ?? null;
  return { target: row.id, changes: { ...changes, updatedAt: now }, expected };
}
export function remove(row, now) {
  return { target: row.id, expected: { lifecycle: row.lifecycle ?? null }, deletedAt: now, updatedAt: now };
}
export function rename(p, name) {
  return { name, expected: p.nameRevision ?? null };
}
// The reducer compares these facts with the stored row exactly, so only values whose JSON spelling survives a round trip go back.
const required = ["key", "name", "quantity", "unit", "checkedAt"];
const kept = [...required, "rawText", "note", "gtin", "store", "fromSuggestion", "addedBy"];
/** Moves the picked-up items into the list's purchase history as one trip. Null when nothing can be finished. */
export function finish(p, now) {
  const picked = rows(p).filter((row) => isChecked(row) && required.every((f) => Object.hasOwn(row.facts, f)) &&
    Number.isSafeInteger(row.facts.checkedAt));
  if (!picked.length) return null;
  return {
    tripId: crypto.randomUUID(),
    closedAt: now,
    rows: picked.map((row) => ({
      id: row.id,
      facts: Object.fromEntries(kept.filter((f) => Object.hasOwn(row.facts, f)).map((f) => [f, row.facts[f]])),
      expectedLifecycle: row.lifecycle ?? null,
      expectedFields: row.revisions,
    })),
  };
}
export function undo(finishIntent) {
  return { finishId: finishIntent.id, tripId: finishIntent.payload.tripId };
}
