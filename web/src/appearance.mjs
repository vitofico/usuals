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
