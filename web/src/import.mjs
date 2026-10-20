import { requireThat } from "./wire.mjs";
const headingKey = value => value.toLowerCase().normalize("NFD").replace(/\p{M}+/gu, "").trim().replace(/:$/, "");
const token = value => value.toLowerCase().replace(/ñ/g, "\u0000").normalize("NFD").replace(/\p{M}+/gu, "").replace(/\u0000/g, "ñ");
const words = (packs, key) => packs.flatMap(p => (p[key] ?? "").split(",").map(x => x.trim()).filter(Boolean));
const pairs = (packs, key) => new Map(words(packs, key).map(x => { const at = x.indexOf(":"); return [x.slice(0, at), x.slice(at + 1)]; }));
const clean = value => value.replace(/!\[[^\]]*\]\([^)]*\)/g, "").replace(/\[([^\]]+)\]\([^)]*\)/g, "$1")
  .replace(/<[^>]+>/g, " ").replace(/\*\*|__|`|\*|_/g, "").replace(/\s+/g, " ").trim();
const heading = value => { const m = /^(#{1,6})\s+(.+)$/.exec(value.trim()); return m && [m[1].length, clean(m[2].replace(/[# ]+$/, ""))]; };
const bullet = /^(?:[-+*]|\d+[.)])\s+(.+)$/;
export function markdownDocument(text, packs) {
  requireThat(typeof text === "string" && text.length <= 30000, "importTooLarge");
  const visible = [], lines = text.replace(/^\uFEFF/, "").split(/\r?\n/);
  let fence = null, front = lines[0]?.trim() === "---";
  for (let index = 0; index < lines.length; index++) {
    const raw = lines[index], line = raw.trim();
    if (front) { if (index > 0 && line === "---") front = false; continue; }
    const marker = /^(?:`{3,}|~{3,})/.exec(line)?.[0];
    if (fence) { if (marker && marker[0] === fence[0] && marker.length >= fence.length && !line.slice(marker.length).trim()) fence = null; continue; }
    if (marker) { fence = marker; continue; }
    const previous = visible.at(-1);
    if (/^(?:={3,}|-{3,})$/.test(line) && previous?.trim() && !bullet.test(previous.trim()) && !heading(previous))
      visible[visible.length - 1] = (line[0] === "=" ? "# " : "## ") + previous.trim();
    else visible.push(raw);
  }
  const ingredients = new Set(words(packs, "ingredient-headings").map(headingKey)), ignored = new Set(words(packs, "ignored-recipe-headings").map(headingKey));
  const recipe = visible.some(x => { const h = heading(x); return h && ingredients.has(headingKey(h[1])); });
  let title = null, ingredientDepth = null, ignoredDepth = null, accept = !recipe;
  const entries = [];
  for (const raw of visible) {
    const line = raw.trim(), h = heading(line);
    if (h) {
      const [depth, name] = h, key = headingKey(name);
      if (!title && depth === 1 && !ingredients.has(key) && !ignored.has(key)) title = name;
      if (ingredients.has(key)) { ingredientDepth = depth; ignoredDepth = null; accept = true; }
      else if (ignored.has(key)) { ingredientDepth = null; ignoredDepth = depth; accept = false; }
      else if (ingredientDepth !== null && depth > ingredientDepth) accept = true;
      else if (ignoredDepth !== null && depth > ignoredDepth) accept = false;
      else { ingredientDepth = null; ignoredDepth = null; accept = !recipe; }
      continue;
    }
    if (!accept || !line || line.startsWith(">") || line === "---") continue;
    const entry = bullet.exec(line)?.[1] ?? (ingredientDepth !== null ? line : null);
    if (!entry || /^\[[xX]\]\s*/.test(entry)) continue;
    const value = clean(entry.replace(/^\[ \]\s*/, ""));
    if (value && !/^https?:\/\/\S+$/i.test(value)) entries.push(value);
    requireThat(entries.length <= 150, "importTooLarge");
  }
  return { title, entries };
}
export function ingredient(line, packs) {
  const original = line, numbers = pairs(packs, "numbers"), units = pairs(packs, "units"), multipliers = pairs(packs, "multipliers"), measures = words(packs, "recipe-measures").map(x => x.split(" ")).sort((a,b) => b.length-a.length);
  let value = line.trim(), quantity = null, unit = null;
  const unicode = /^(\d*)\s*([½¼¾⅓⅔⅛⅜⅝⅞])/.exec(value), fraction = /^(?:(\d+)\s+)?(\d+)\/(\d+)\b/.exec(value);
  if (unicode) {
    const parts = { "½": .5, "¼": .25, "¾": .75, "⅓": 1 / 3, "⅔": 2 / 3, "⅛": .125, "⅜": .375, "⅝": .625, "⅞": .875 };
    value = (Number(unicode[1] || 0) + parts[unicode[2]]) + " " + value.slice(unicode[0].length).trimStart();
  } else if (fraction && Number(fraction[3]) > 0) value = (Number(fraction[1] || 0) + Number(fraction[2]) / Number(fraction[3])) + " " + value.slice(fraction[0].length).trimStart();
  const tokens = value.split(/\s+/), prefix = /^(\d+(?:[.,]\d+)?)\s*([\p{L}]+)\b/iu.exec(value);
  if (prefix && units.has(token(prefix[2]))) {
    quantity = Number(prefix[1].replace(",", ".")); unit = units.get(token(prefix[2])); value = value.slice(prefix[0].length).trim();
  } else {
    const numeric = tokens[0]?.replace(",", "."), n = /^\d+(?:\.\d+)?$/.test(numeric) ? Number(numeric) : Number(numbers.get(token(tokens[0] ?? "")));
    if (Number.isFinite(n) && tokens.length > 1) {
      const second = token(tokens[1]), measure = measures.find(parts => parts.every((part,i) => token(tokens[i+1] ?? "") === part));
      if (units.has(second)) { quantity = n; unit = units.get(second); value = tokens.slice(2).join(" "); }
      else if (multipliers.has(second)) { quantity = n * Number(multipliers.get(second)); unit = "PIECE"; value = tokens.slice(2).join(" "); }
      else if (measure) value = tokens.slice(1 + measure.length).join(" ");
      else { quantity = n; unit = "PIECE"; value = tokens.slice(1).join(" "); }
    }
  }
  const connectors = new Set(words(packs, "connectors"));
  if (connectors.has(token(value.split(" ")[0] ?? ""))) value = value.slice(value.indexOf(" ") + 1);
  if (!Number.isFinite(quantity) || quantity <= 0 || quantity > 1e9) { quantity = null; unit = null; }
  const name = value.split(/[;(]|,(?!\d)/)[0].trim().slice(0, 120);
  return name ? { name, quantity, unit, original, note: original === name ? null : original.slice(0, 200) } : null;
}
