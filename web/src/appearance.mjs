export const palettes = Object.freeze(["plum", "grove", "tide"]);
const key = "usuals-palette";
export const paletteId = (id) => palettes.includes(id) ? id : "plum";

export function readPalette(storage) {
  try { return paletteId(storage.getItem(key)); } catch { return "plum"; }
}

export function writePalette(id, storage) {
  const selected = paletteId(id);
  try { storage.setItem(key, selected); } catch { /* The live choice still works without storage. */ }
  return selected;
}

export const backgroundStyles = Object.freeze(["plain", "artwork"]);
const backgroundKey = "usuals-background-style";
export const backgroundStyleId = (id) => backgroundStyles.includes(id) ? id : "artwork";

export function readBackgroundStyle(storage) {
  try { return backgroundStyleId(storage.getItem(backgroundKey)); } catch { return "artwork"; }
}

export function writeBackgroundStyle(id, storage) {
  const selected = backgroundStyleId(id);
  try { storage.setItem(backgroundKey, selected); } catch { /* Keep the live choice when storage is unavailable. */ }
  return selected;
}
