// The app's rule-based capture parser (LocalUtteranceParser in :core:parse), driven by the same input-language packs.
import { words, pairs } from "./import.mjs";

const TOKEN_EDGE = ".,;:!?¡¿\"'`()[]{}";
const NAME_EDGE = ".,;:!?¡¿\"'`()[]{}*·•_/\\|-";
const DECIMAL = /^\d+(?:[.,]\d+)?$/, GLUED = /^(\d+(?:[.,]\d+)?)([a-zñ]+)$/u, TIMES = /^[x×](\d+)$/;
const trimChars = (text, chars) => {
  let start = 0, end = text.length;
  while (start < end && chars.includes(text[start])) start++;
  while (end > start && chars.includes(text[end - 1])) end--;
  return text.slice(start, end);
};
const escape = (text) => text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

/** Kotlin's foldToken: lowercase, accents dropped except the tilde of ñ, edge punctuation trimmed. */
export function foldToken(raw) {
  let folded = "";
  for (const c of raw.toLowerCase().normalize("NFD"))
    if (c === "̃" && folded.endsWith("n")) folded += c;
    else if (!/\p{M}/u.test(c)) folded += c;
  return trimChars(folded.normalize("NFC"), TOKEN_EDGE);
}

/** Returns `(utterance) => [{ name, quantity, unit, note }]`. It never throws and never yields a blank name. */
export function utteranceParser(packs) {
  const set = (key) => new Set(words(packs, key));
  const numbers = new Map([...pairs(packs, "numbers")].map(([k, v]) => [k, Number(v)]));
  const multipliers = new Map([...pairs(packs, "multipliers")].map(([k, v]) => [k, Number(v)]));
  const units = pairs(packs, "units"), noise = set("noise"), connectors = set("connectors"), articles = set("articles");
  const conjunctions = set("conjunctions");
  const nonNames = new Set([...connectors, ...articles, ...conjunctions, ...words(packs, "non-names")]);
  const noteMarkers = [...set("note-markers")].map((marker) => new RegExp(escape(` ${marker} `), "iu"));
  const separator = new RegExp(String.raw`\s*[\r\n]+\s*|\s*(?<![0-9])[,;](?![0-9])\s*|\s+(?:` +
    [...conjunctions].sort().map(escape).join("|") + String.raw`)\s+`, "iu");

  const decimal = (token) => DECIMAL.test(token) ? Number(token.replace(",", ".")) : null;
  const numberValue = (token) => numbers.get(token) ?? decimal(token);
  const glued = (token) => {
    const match = GLUED.exec(token), amount = match && decimal(match[1]), unit = match && units.get(match[2]);
    return amount != null && unit ? { amount, unit } : null;
  };
  const taken = (amount, unit, consumed) => ({ quantity: { amount, unit }, consumed });

  function leadingQuantity(folded) {
    if (!folded.length) return null;
    const first = glued(folded[0]);
    if (first) return taken(first.amount, first.unit, 1);
    const amount = numberValue(folded[0]);
    let cursor = amount != null ? 1 : 0;
    if (amount == null && !multipliers.has(folded[0]) && !units.has(folded[0])) return null;
    // "half a litre": English slips an article between the number and the unit.
    if (amount != null && articles.has(folded[cursor]) && (units.has(folded[cursor + 1]) || multipliers.has(folded[cursor + 1]))) cursor++;
    if (cursor < folded.length) {
      const multiplier = multipliers.get(folded[cursor]);
      if (multiplier != null) return taken((amount ?? 1) * multiplier, "PIECE", cursor + 1);
      const unit = units.get(folded[cursor]);
      if (unit) {
        if (amount == null && !connectors.has(folded[cursor + 1])) return null;
        return taken(amount ?? 1, unit, cursor + 1);
      }
    }
    return amount == null ? null : taken(amount, "PIECE", cursor);
  }

  function trailingQuantity(folded) {
    const last = folded.at(-1);
    if (last == null) return null;
    const times = TIMES.exec(last), count = times && Number.parseInt(times[1], 10);
    if (count > 0 && count <= 2147483647) return taken(count, "PIECE", 1);
    const end = glued(last);
    if (end) return taken(end.amount, end.unit, 1);
    const unit = units.get(last), amount = folded.length >= 2 ? numberValue(folded.at(-2)) : null;
    return unit && amount != null ? taken(amount, unit, 2) : null;
  }

  function segment(raw) {
    const notes = [];
    let text = raw.trim();
    if (text.includes("(")) text = text.replace(/\(([^)]*)\)/g, (_, inner) => { if (inner.trim()) notes.push(inner.trim()); return " "; }).trim();
    // A trailing "for ..." keeps its marker: the note reads back as a phrase.
    for (const marker of noteMarkers) {
      const index = marker.exec(text)?.index ?? -1;
      if (index <= 0 || !text.slice(0, index).trim()) continue;
      notes.push(text.slice(index).trim());
      text = text.slice(0, index).trim();
      break;
    }
    let tokens = text.split(/\s+/).filter((word) => word.trim());
    let skip = 0;
    while (skip < tokens.length && noise.has(foldToken(tokens[skip]))) skip++;
    tokens = tokens.slice(skip);
    if (!tokens.length) return null;
    let quantity = null;
    const leading = leadingQuantity(tokens.map(foldToken));
    if (leading) {
      quantity = leading.quantity;
      tokens = tokens.slice(leading.consumed);
      if (tokens.length && connectors.has(foldToken(tokens[0]))) tokens = tokens.slice(1);
    } else {
      const trailing = trailingQuantity(tokens.map(foldToken));
      if (trailing && tokens.length > trailing.consumed) {
        quantity = trailing.quantity;
        tokens = tokens.slice(0, -trailing.consumed);
      }
    }
    const name = trimChars(tokens.join(" ").trim(), NAME_EDGE).trim();
    if (!name || !/\p{L}/u.test(name) || name.split(/\s+/).every((word) => nonNames.has(foldToken(word)))) return null;
    const note = notes.filter((n) => n.trim()).join(", ");
    return { name, quantity: quantity?.amount ?? null, unit: quantity?.unit ?? null, note: note || null };
  }

  return (utterance) => {
    if (typeof utterance !== "string" || !utterance.trim()) return [];
    return utterance.split(separator).map(segment).filter(Boolean);
  };
}
