import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { palettes, paletteId, readPalette, writePalette, backgroundStyleId, readBackgroundStyle, writeBackgroundStyle } from "../src/appearance.mjs";

test("background artwork defaults on and a deliberate plain choice survives palette changes and reloads", () => {
  const values = new Map();
  const storage = { getItem: (key) => values.get(key), setItem: (key, value) => values.set(key, value) };
  assert.equal(readBackgroundStyle(storage), "artwork");
  writeBackgroundStyle("plain", storage);
  writePalette("grove", storage);
  assert.equal(readBackgroundStyle(storage), "plain");
  assert.equal(readPalette(storage), "grove");
  writeBackgroundStyle("artwork", storage);
  assert.equal(readBackgroundStyle(storage), "artwork");
  assert.equal(readPalette(storage), "grove");
  for (const unknown of [null, "future", "__proto__", {}]) assert.equal(backgroundStyleId(unknown), "artwork");
});

test("background choice still works when browser storage is unavailable", () => {
  const blocked = { getItem() { throw Error("blocked"); }, setItem() { throw Error("full"); } };
  assert.equal(readBackgroundStyle(blocked), "artwork");
  assert.equal(readBackgroundStyle(null), "artwork");
  assert.equal(writeBackgroundStyle("plain", blocked), "plain");
  assert.equal(writeBackgroundStyle("plain", null), "plain");
});

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
const artworkCss = await readFile(new URL("../assets/collage.css", import.meta.url), "utf8");
const ambientOpacity = Math.max(...[...artworkCss.matchAll(/\.(?:ambient|list-heading)-collage\s*\{([^}]+)\}/g)]
  .flatMap((m) => [...m[1].matchAll(/opacity:\s*([\d.]+)/g)].map((v) => Number(v[1]))));
assert.ok(ambientOpacity > 0 && ambientOpacity <= 1);
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
    for (const [foreground, background] of [["ink", "paper"], ["muted", "paper"], ["muted", "ground"], ["primary", "ground"],
      ["on-action", "primary"], ["gold", "champagne"], ["muted", "mauve"], ["ink", "mauve"]]) {
      assert.ok(contrast(values[`--${foreground}`], values[`--${background}`]) >= 4.5,
        `${foreground}/${background}`);
    }
    assert.ok(contrast(values["--outline"], values["--paper"]) >= 3);
    const rgb = (hex) => [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16));
    const blend = (a, b, amount) => a.map((v, i) => Math.round(v * (1 - amount) + b[i] * amount));
    const hex = (channels) => "#" + channels.map((v) => v.toString(16).padStart(2, "0")).join("");
    const layers = [
      blend(rgb(values["--champagne"]), rgb(values["--gold"]), night ? 0.45 : 0.16),
      blend(rgb(values["--collage-leaf"]), rgb(values["--collage-on-leaf"]), 0.35),
      rgb(values["--paper"]), rgb(values["--primary"]),
    ];
    // Muted captions keep opaque ground backings; the title and header icons sit directly on the artwork.
    for (const layer of layers) {
      const paintedGround = hex(blend(rgb(values["--ground"]), layer, ambientOpacity));
      assert.ok(contrast(values["--ink"], paintedGround) >= 4.5, "main text over the strongest ambient layer");
      for (const icon of ["--primary", "--muted"]) {
        assert.ok(contrast(values[icon], paintedGround) >= 3, "unbacked header icons over ambient artwork");
      }
    }
  });
}
