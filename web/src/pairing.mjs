// Pairing v2: the invite key lives only in the link, so the relay cannot vouch for keys it substituted.
import { b64, unb64, bytes, digest, uuid, uuidText, requireThat, Problem, text, utf8 } from "./wire.mjs";

async function derive(key, info) {
  const base = await crypto.subtle.importKey("raw", key, "HKDF", false, ["deriveBits"]);
  return new Uint8Array(await crypto.subtle.deriveBits({ name: "HKDF", hash: "SHA-256", salt: new Uint8Array(), info: utf8.encode(info) }, base, 256));
}
export const admitKey = (key) => derive(key, "USUALS/INVITE/2/admit");
export const macKey = (key) => derive(key, "USUALS/INVITE/2/mac");

async function hmac(key, label, message) {
  const k = await crypto.subtle.importKey("raw", key, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return new Uint8Array(await crypto.subtle.sign("HMAC", k, bytes(label, message)));
}
const sixDigits = (b) => String(new DataView(b.buffer, b.byteOffset, 4).getUint32(0) % 1_000_000).padStart(6, "0");

export async function joinMac(key, assertion) { return b64(await hmac(key, "USUALS/JOIN-MAC/2\0", assertion)); }
export async function verifyJoinMac(key, assertion, mac) {
  if (typeof mac !== "string") return false;
  let given; try { given = unb64(mac, 32); } catch { return false; }
  const k = await crypto.subtle.importKey("raw", key, { name: "HMAC", hash: "SHA-256" }, false, ["verify"]);
  return crypto.subtle.verify("HMAC", k, given, bytes("USUALS/JOIN-MAC/2\0", assertion));
}
export async function joinCode(key, assertion) { return sixDigits(await hmac(key, "USUALS/JOIN-CODE/2\0", assertion)); }
export async function descriptorAnchor(descriptor) { return (await digest(bytes("USUALS/INVITE/2\0", descriptor))).slice(0, 16); }
export function connectionCode(fingerprintHex) {
  requireThat(/^[0-9a-f]{64}$/.test(fingerprintHex));
  return sixDigits(Uint8Array.from(fingerprintHex.slice(0, 8).match(/../g), (v) => parseInt(v, 16)));
}

function origin(value) {
  const url = new URL(value);
  requireThat(url.origin === value && !url.username && !url.password &&
    (url.protocol === "https:" || (url.protocol === "http:" && ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname))));
  return value;
}
export function inviteLink(at, inviteId, key, anchor) {
  requireThat(key.length === 32 && anchor.length === 16);
  return origin(at) + "/join#2." + b64(bytes(uuid(inviteId), key, anchor));
}
export function appLink(link) { return "usuals://join#" + b64(utf8.encode(link)); }
export function parseInviteLink(value) {
  requireThat(typeof value === "string" && value.length <= 512);
  if (value.startsWith("usuals://join#")) {
    const inner = unb64(value.slice("usuals://join#".length));
    if (inner[0] === 0x7b) throw new Problem("upgrade");
    const link = text.decode(inner);
    requireThat(!link.startsWith("usuals:"));
    return parseInviteLink(link);
  }
  const at = value.indexOf("#");
  requireThat(at > 0);
  const base = value.slice(0, at), fragment = value.slice(at + 1);
  requireThat(base.endsWith("/join") && fragment.startsWith("2."));
  const raw = unb64(fragment.slice(2), 64);
  return { origin: origin(base.slice(0, -"/join".length)), inviteId: uuidText(raw.slice(0, 16)), key: raw.slice(16, 48), anchor: raw.slice(48) };
}
