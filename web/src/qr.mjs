import { qrcodegen } from "./vendor/qrcodegen.mjs";
import { requireThat } from "./wire.mjs";

export function qrMatrix(value) {
  requireThat(typeof value === "string" && value.length <= 4096);
  const qr = qrcodegen.QrCode.encodeText(value, qrcodegen.QrCode.Ecc.MEDIUM);
  return Array.from({ length: qr.size }, (_, y) =>
    Array.from({ length: qr.size }, (_, x) => qr.getModule(x, y)));
}
