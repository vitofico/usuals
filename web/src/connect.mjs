// A signed-in browser's one-scan QR for the person's own phone: the server approves the phone that holds the secret at once.
import { b64, unb64, bytes, uuid, uuidText, requireThat, utf8 } from "./wire.mjs";

function origin(value) {
  const url = new URL(value);
  requireThat(url.origin === value && !url.username && !url.password &&
    (url.protocol === "https:" || (url.protocol === "http:" && ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname))));
  return value;
}
/** `https://<origin>/connect#1.<b64url(offerId16 || secret32)>`, the form the phone parses. */
export function connectLink(at, offerId, secret) {
  requireThat(secret.length === 32);
  return origin(at) + "/connect#1." + b64(bytes(uuid(offerId), secret));
}
export function connectAppLink(link) { return "usuals://connect#" + b64(utf8.encode(link)); }
export function parseConnectLink(value) {
  requireThat(typeof value === "string" && value.length <= 512);
  const at = value.indexOf("#");
  requireThat(at > 0);
  const base = value.slice(0, at), fragment = value.slice(at + 1);
  requireThat(base.endsWith("/connect") && fragment.startsWith("1."));
  const raw = unb64(fragment.slice(2), 48);
  return { origin: origin(base.slice(0, -"/connect".length)), offerId: uuidText(raw.slice(0, 16)), secret: raw.slice(16) };
}
/** The 6-digit code a phone that signs in through this browser shows, so the person approves the right phone. */
export function connectionCode(fingerprintHex) {
  requireThat(/^[0-9a-f]{64}$/.test(fingerprintHex));
  return String(parseInt(fingerprintHex.slice(0, 8), 16) % 1_000_000).padStart(6, "0");
}
