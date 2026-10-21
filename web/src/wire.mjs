export const utf8 = new TextEncoder();
export const text = new TextDecoder("utf-8", { fatal: true });
export class Problem extends Error {
  constructor(code = "invalid") {
    super(code);
    this.code = code;
  }
}
export function requireThat(ok, code = "invalid") {
  if (!ok) throw new Problem(code);
}
export function bytes(...values) {
  const arrays = values.map((v) =>
    typeof v === "string" ? utf8.encode(v) : v,
  );
  const out = new Uint8Array(arrays.reduce((n, a) => n + a.length, 0));
  let at = 0;
  for (const a of arrays) {
    out.set(a, at);
    at += a.length;
  }
  return out;
}
export function b64(value) {
  let s = "";
  for (let i = 0; i < value.length; i += 8192)
    s += String.fromCharCode(...value.subarray(i, i + 8192));
  return btoa(s).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}
export function unb64(value, size) {
  requireThat(typeof value === "string" && /^[A-Za-z0-9_-]+$/.test(value));
  let raw;
  try {
    raw = atob(value.replaceAll("-", "+").replaceAll("_", "/"));
  } catch {
    throw new Problem();
  }
  const out = Uint8Array.from(raw, (c) => c.charCodeAt(0));
  requireThat(
    b64(out) === value && (size === undefined || out.length === size),
  );
  return out;
}
export function uuid(value) {
  requireThat(
    typeof value === "string" &&
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(
        value,
      ),
  );
  return Uint8Array.from(value.replaceAll("-", "").match(/../g), (v) =>
    parseInt(v, 16),
  );
}
export function uuidText(value) {
  const h = [...value].map((v) => v.toString(16).padStart(2, "0")).join("");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}
export async function hash(value) {
  return b64(new Uint8Array(await crypto.subtle.digest("SHA-256", value)));
}
