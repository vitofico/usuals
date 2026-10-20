import test from "node:test";
import assert from "node:assert/strict";
import { qrMatrix, verificationQr, VerificationCamera } from "../src/qr.mjs";
import jsQR from "../src/vendor/jsqr.mjs";
import { b64, jsonBytes } from "../src/wire.mjs";

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
test("locally generated invitation and full signed-assertion QR round-trip through the independent decoder", () => {
  for (const prefix of ["usuals://join#", "usuals://device#"]) {
    const value = prefix + b64(jsonBytes({ assertion: "a".repeat(1400), origin: "https://usuals.example" }));
    const { data, size } = pixels(qrMatrix(value));
    assert.equal(jsQR(data, size, size).data, value);
  }
});
test("verification QR is bounded, strict, exact-field and disjoint from product/invitation capture", () => {
  const pending = { requestId: "00000000-0000-4000-8000-000000000001", assertion: b64(new Uint8Array([1, 2])), signature: b64(new Uint8Array(64)) };
  const uri = "usuals://device#" + b64(jsonBytes(pending));
  assert.deepEqual(verificationQr(uri), pending);
  for (const bad of ["4006381333931", "usuals://join#" + uri.split("#")[1], "usuals://device#" + "a".repeat(4096),
    "usuals://device#" + b64(jsonBytes({ ...pending, extra: true })),
    "usuals://device#" + b64(jsonBytes({ ...pending, signature: b64(new Uint8Array(32)) })),
    "usuals://device#" + b64(new TextEncoder().encode('{"requestId":"x","requestId":"y"}'))])
    assert.throws(() => verificationQr(bad));
});
function cameraFixture(overrides = {}) {
  let stopped = 0, failure = 0, callback;
  const codes = [], stream = { getTracks: () => [{ stop: () => stopped++ }] };
  const video = { readyState: 2, videoWidth: 1920, videoHeight: 1080, play: async () => {} };
  const canvas = { getContext: () => ({ drawImage: (source, ...args) => {
    assert.equal(source, video); assert.deepEqual(args, [420, 0, 1080, 1080, 0, 0, 800, 800]);
  }, getImageData: () => ({ data: new Uint8ClampedArray(4) }) }) };
  const camera = new VerificationCamera(video, canvas, code => codes.push(code), () => failure++, {
    media: { getUserMedia: async constraints => { assert.equal(constraints.audio, false); return stream; } },
    decode: () => null, schedule: fn => { callback = fn; return 7; }, cancel: () => {}, ...overrides,
  });
  return { camera, stream, video, canvas, codes, tick: () => callback(), stopped: () => stopped, failure: () => failure };
}
test("camera permission resolved after dialog close cannot restart or retain the camera", async () => {
  let allow;
  const f = cameraFixture({ media: { getUserMedia: () => new Promise(resolve => allow = resolve) } });
  const starting = f.camera.start(); f.camera.stop(); allow(f.stream); await starting;
  assert.equal(f.stopped(), 1); assert.equal(f.video.srcObject, null); assert.deepEqual(f.codes, []);
});
test("camera ignores other codes, bounds frame size and delivers a verification QR only once", async () => {
  let result = { data: "usuals://join#private" };
  const f = cameraFixture({ decode: (_, width, height) => {
    assert.equal(width, 800); assert.equal(height, 800); return result;
  } });
  await f.camera.start(); assert.deepEqual(f.codes, []);
  result = { data: "usuals://device#public-signed-assertion" }; f.tick(); f.camera.tick();
  assert.deepEqual(f.codes, [result.data]); assert.equal(f.stopped(), 1);
  assert.equal(f.video.srcObject, null); assert.equal(f.canvas.width, 0);
});
test("permission and camera playback failures give a fallback and release every acquired track", async () => {
  const refused = cameraFixture({ media: { getUserMedia: async () => { throw Error(); } } });
  await refused.camera.start(); assert.equal(refused.failure(), 1); assert.equal(refused.stopped(), 0);
  const f = cameraFixture(); f.video.play = async () => { throw Error(); };
  await f.camera.start(); assert.equal(f.failure(), 1); assert.equal(f.stopped(), 1); assert.equal(f.video.srcObject, null);
});
