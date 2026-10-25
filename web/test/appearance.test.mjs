import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { palettes, paletteId, readPalette, writePalette } from "../src/appearance.mjs";

test("palette selection survives storage round trips and rejects unknown values", () => {
  const values = new Map();
  const storage = { getItem: (key) => values.get(key), setItem: (key, value) => values.set(key, value) };
  assert.equal(readPalette(storage), "plum");
  for (const palette of palettes) {
    assert.equal(writePalette(palette, storage), palette);
    assert.equal(readPalette(storage), palette);
  }
  for (const unknown of [null, "future", "__proto__", {}, "GROVE"]) assert.equal(paletteId(unknown), "plum");
  writePalette("future", storage);
  assert.equal(readPalette(storage), "plum");
});

test("unavailable or full browser storage still permits a live palette choice", () => {
  const blocked = { getItem() { throw Error("blocked"); }, setItem() { throw Error("full"); } };
  assert.equal(readPalette(blocked), "plum");
  assert.equal(readPalette(null), "plum");
  assert.equal(writePalette("grove", blocked), "grove");
  assert.equal(writePalette("tide", null), "tide");
});

const css = await readFile(new URL("../assets/theme.css", import.meta.url), "utf8");
const darkStart = css.indexOf("@media (prefers-color-scheme: dark)");
assert.ok(darkStart > 0);
const blocks = (text) => [...text.matchAll(/:root(?:\[data-palette="(\w+)"\])?\s*\{([^}]+)\}/g)]
  .map((m) => [m[1] ?? "plum", Object.fromEntries([...m[2].matchAll(/(--[\w-]+):\s*(#[a-f0-9]{6})\s*;/gi)].map((v) => [v[1], v[2]]))]);
const light = blocks(css.slice(0, darkStart)), dark = blocks(css.slice(darkStart));
function luminance(hex) {
  const rgb = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
    .map((v) => v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4);
  return rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722;
}
function contrast(a, b) {
  const x = luminance(a), y = luminance(b);
  return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
}
for (const palette of palettes) for (const night of [false, true]) {
  test(`${palette}/${night ? "dark" : "light"} preserves text and control contrast`, () => {
    const values = Object.assign({}, ...light.filter(([id]) => id === "plum" || id === palette).map(([, v]) => v),
      ...(night ? dark.filter(([id]) => id === "plum" || id === palette).map(([, v]) => v) : []));
    for (const [foreground, background] of [["ink", "paper"], ["muted", "paper"], ["primary", "ground"],
      ["on-action", "primary"], ["gold", "champagne"], ["muted", "mauve"], ["ink", "mauve"]]) {
      assert.ok(contrast(values[`--${foreground}`], values[`--${background}`]) >= 4.5,
        `${foreground}/${background}`);
    }
    assert.ok(contrast(values["--outline"], values["--paper"]) >= 3);
  });
}
