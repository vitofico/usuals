import test from "node:test";
import assert from "node:assert/strict";
import { qrMatrix } from "../src/qr.mjs";
import jsQR from "../src/vendor/jsqr.mjs";
import { b64 } from "../src/wire.mjs";

function pixels(matrix, scale = 4) {
  const size = (matrix.length + 8) * scale, data = new Uint8ClampedArray(size * size * 4).fill(255);
  matrix.forEach((row, y) => row.forEach((dark, x) => {
    if (dark) for (let dy = 0; dy < scale; dy++) for (let dx = 0; dx < scale; dx++) {
      const at = (((y + 4) * scale + dy) * size + (x + 4) * scale + dx) * 4;
      data[at] = data[at + 1] = data[at + 2] = 0;
    }
  }));
  return { data, size };
}
test("invite links and their app wrapper round-trip through the independent decoder", () => {
  const link = "https://usuals.example/join#2." + b64(new Uint8Array(64).fill(9));
  for (const value of [link, "usuals://join#" + b64(new TextEncoder().encode(link))]) {
    const { data, size } = pixels(qrMatrix(value));
    assert.equal(jsQR(data, size, size).data, value);
  }
});
