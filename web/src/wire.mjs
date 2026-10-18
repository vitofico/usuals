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
export function uint(value, size = 8) {
  requireThat(Number.isSafeInteger(value) && value >= 0, "upgrade_required");
  const out = new Uint8Array(size),
    view = new DataView(out.buffer);
  if (size === 8) view.setBigUint64(0, BigInt(value));
  else if (size === 4) view.setUint32(0, value);
  else view.setUint16(0, value);
  return out;
}
export function field(value) {
  return bytes(uint(value.length, 4), value);
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
  const h = hex(value);
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}
export function hex(value) {
  return [...value].map((v) => v.toString(16).padStart(2, "0")).join("");
}
export function domain(name, ...values) {
  return bytes(`USUALS/${name}/1\0`, ...values.map(field));
}
export const zero = b64(new Uint8Array(32));
export async function digest(value) {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", value));
}
export async function hash(value) {
  return b64(await digest(value));
}
export async function fingerprint(server, household, device) {
  return hash(
    bytes(
      "USUALS/FINGERPRINT/1\0",
      unb64(server, 32),
      uuid(household),
      unb64(device.signingPublicKey, 32),
      unb64(device.recipientPublicKey, 32),
    ),
  );
}
export function encodeHeader(h) {
  requireThat(
    (h.protocol ?? 1) === 1 && (h.payloadSchema ?? 1) === 1 && h.epoch > 0,
  );
  return bytes(
    uint(1, 2),
    unb64(h.serverId, 32),
    ...[
      "householdId",
      "listId",
      "authorDeviceId",
      "intentId",
      "envelopeId",
    ].map((k) => uuid(h[k])),
    uint(h.epoch),
    unb64(h.manifestHash, 32),
    uint(h.baseSeq),
    unb64(h.baseHead, 32),
    uint(1, 2),
  );
}
export function parseHeader(encoded) {
  const b = unb64(encoded, 196),
    v = new DataView(b.buffer);
  let at = 0;
  const read = (n) => {
      const out = b.slice(at, at + n);
      at += n;
      return out;
    },
    small = () => {
      const n = v.getUint16(at);
      at += 2;
      return n;
    },
    integer = () => {
      const n = v.getBigUint64(at);
      at += 8;
      requireThat(n <= BigInt(Number.MAX_SAFE_INTEGER), "upgrade_required");
      return Number(n);
    };
  const h = { protocol: small(), serverId: b64(read(32)) };
  for (const k of [
    "householdId",
    "listId",
    "authorDeviceId",
    "intentId",
    "envelopeId",
  ])
    h[k] = uuidText(read(16));
  Object.assign(h, {
    epoch: integer(),
    manifestHash: b64(read(32)),
    baseSeq: integer(),
    baseHead: b64(read(32)),
    payloadSchema: small(),
  });
  requireThat(h.protocol === 1 && h.payloadSchema === 1, "upgrade_required");
  requireThat(h.epoch > 0);
  return h;
}
export function signingBytes(e) {
  return domain("ENVELOPE", unb64(e.header, 196), unb64(e.ciphertext));
}
export async function envelopeHash(e) {
  return hash(bytes(signingBytes(e), unb64(e.signature, 64)));
}
export async function head(previous, seq, e) {
  return hash(
    bytes(
      "USUALS/HEAD/1\0",
      unb64(previous, 32),
      uint(seq),
      await digest(signingBytes(e)),
      unb64(e.signature, 64),
    ),
  );
}
export function receiptBytes(server, household, list, seq, headValue) {
  requireThat(seq > 0);
  return bytes(
    "USUALS/RECEIPT/1\0",
    unb64(server, 32),
    uuid(household),
    uuid(list),
    uint(seq),
    unb64(headValue, 32),
  );
}
export function authBytes(origin, server, device, c, purpose) {
  return bytes(
    "USUALS/AUTH/1\0",
    field(utf8.encode(origin)),
    unb64(server, 32),
    uuid(device.id),
    uuid(c.challengeId),
    unb64(c.nonce, 32),
    uint(c.expiresAt),
    field(utf8.encode(purpose)),
    unb64(device.signingPublicKey, 32),
  );
}
export function packageContext(server, household, p) {
  return bytes(
    "USUALS/KEY/1\0",
    unb64(server, 32),
    uuid(household),
    uuid(p.listId),
    uint(p.epoch),
    unb64(p.predecessorHash, 32),
    unb64(p.recipientFingerprint, 32),
  );
}
export function packageBytes(server, household, p) {
  return domain(
    "PACKAGE",
    packageContext(server, household, p),
    unb64(p.ciphertext),
  );
}
export async function packageHash(server, household, p) {
  return hash(
    bytes(packageBytes(server, household, p), unb64(p.signature, 64)),
  );
}
export function manifestBytes(p) {
  return domain(
    "MEMBERSHIP",
    unb64(p.manifestBytes),
    p.transferAcceptance
      ? unb64(p.transferAcceptance.signature, 64)
      : new Uint8Array(),
  );
}
export async function manifestHash(p) {
  return hash(bytes(manifestBytes(p), unb64(p.signature, 64)));
}
export function joinBytes(a) {
  return bytes(
    "USUALS/JOIN/1\0",
    field(utf8.encode(a.origin)),
    unb64(a.serverId, 32),
    uuid(a.householdId),
    unb64(a.genesisHash, 32),
    uuid(a.inviteId),
    uuid(a.requestId),
    uuid(a.memberId),
    uuid(a.device.id),
    unb64(a.device.signingPublicKey, 32),
    unb64(a.device.recipientPublicKey, 32),
    unb64(a.secretHash, 32),
    uuid(a.challengeId),
    unb64(a.nonce, 32),
    uint(a.expiresAt),
  );
}
const numberTag = "\u0000usuals.number";
export function isLiteralNumber(value) {
  return (
    value !== null &&
    typeof value === "object" &&
    !Array.isArray(value) &&
    Object.keys(value).length === 1 &&
    Object.hasOwn(value, numberTag) &&
    typeof value[numberTag] === "string"
  );
}
export function primitive(value) {
  if (isLiteralNumber(value)) return value[numberTag];
  requireThat(
    value !== null && ["string", "number", "boolean"].includes(typeof value),
  );
  return String(value);
}
export function number(value) {
  const raw = primitive(value).replace(
    /^[\u0000-\u0020]+|[\u0000-\u0020]+$/g,
    "",
  );
  requireThat(
    !/^[+-]?0[xX](?:[0-9a-fA-F]+(?:\.[0-9a-fA-F]*)?|\.[0-9a-fA-F]+)[pP][+-]?[0-9]+[fFdD]?$/.test(
      raw,
    ),
    "number_unsupported",
  );
  requireThat(
    /^[+-]?(?:(?:[0-9]+\.?[0-9]*|\.[0-9]+)(?:[eE][+-]?[0-9]+)?[fFdD]?|Infinity|NaN)$/.test(
      raw,
    ),
  );
  return Number(raw.replace(/[fFdD]$/, ""));
}
export function strictInteger(value) {
  const raw = primitive(value);
  requireThat(/^[+-]?[0-9]+$/.test(raw));
  const exact = BigInt(raw);
  requireThat(exact >= -9223372036854775808n && exact <= 9223372036854775807n);
  requireThat(
    exact >= BigInt(Number.MIN_SAFE_INTEGER) &&
      exact <= BigInt(Number.MAX_SAFE_INTEGER),
    "upgrade_required",
  );
  return Number(exact);
}
export function strictJson(input, max = 1024 * 1024, payload = false) {
  const raw = typeof input === "string" ? utf8.encode(input) : input;
  requireThat(raw.length <= max, "too_large");
  const source = text.decode(raw);
  let at = 0,
    nodes = 0;
  const space = () => {
    while (/[ \r\n\t]/.test(source[at] ?? "~")) at++;
  };
  const string = () => {
    space();
    requireThat(source[at++] === '\"');
    const start = at - 1;
    while (at < source.length) {
      const character = source[at++];
      if (character === "\\") at++;
      else if (character === '\"') return JSON.parse(source.slice(start, at));
    }
    throw new Problem();
  };
  const value = (depth) => {
    space();
    requireThat(depth <= 32 && ++nodes <= (payload ? 1000000 : 65536));
    const character = source[at];
    if (character === "{") {
      at++;
      space();
      const out = {},
        found = new Set();
      if (source[at] !== "}")
        while (true) {
          const key = string();
          requireThat(
            key.length <= (payload ? 1000 : 64) &&
              found.size < (payload ? 20000 : 4096) &&
              !found.has(key) &&
              key !== numberTag,
          );
          found.add(key);
          space();
          requireThat(source[at++] === ":");
          Object.defineProperty(out, key, {
            value: value(depth + 1),
            enumerable: true,
            writable: true,
            configurable: true,
          });
          space();
          if (source[at] !== ",") break;
          at++;
        }
      requireThat(source[at++] === "}");
      return out;
    }
    if (character === "[") {
      at++;
      space();
      const out = [];
      if (source[at] !== "]")
        while (true) {
          out.push(value(depth + 1));
          space();
          if (source[at] !== ",") break;
          at++;
        }
      requireThat(source[at++] === "]");
      return out;
    }
    if (character === '\"') return string();
    const start = at;
    while (at < source.length && !/[,\]} \n\r\t]/.test(source[at])) at++;
    const literal = source.slice(start, at);
    requireThat(
      /^(true|false|-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?)$/.test(
        literal,
      ) ||
        (payload && literal === "null"),
    );
    if (literal === "true") return true;
    if (literal === "false") return false;
    if (literal === "null") return null;
    const parsed = Number(literal);
    requireThat(Number.isFinite(parsed));
    if (Number.isInteger(parsed))
      requireThat(Number.isSafeInteger(parsed), "upgrade_required");
    return JSON.stringify(parsed) === literal
      ? parsed
      : { [numberTag]: literal };
  };
  const parsed = value(0);
  space();
  requireThat(at === source.length);
  return parsed;
}
export function object(value) {
  requireThat(
    value !== null &&
      typeof value === "object" &&
      !Array.isArray(value) &&
      !isLiteralNumber(value),
  );
  return value;
}
export function keys(value, allowed, required = allowed) {
  object(value);
  requireThat(
    Object.keys(value).every((k) => allowed.includes(k)) &&
      required.every((k) => Object.hasOwn(value, k)),
  );
  return value;
}
export function jsonBytes(value) {
  const encode = (item) => {
    if (isLiteralNumber(item)) return primitive(item);
    if (item === null || typeof item !== "object") return JSON.stringify(item);
    if (Array.isArray(item))
      return "[" + item.map((child) => encode(child) ?? "null").join(",") + "]";
    return (
      "{" +
      Object.entries(item)
        .filter(([, child]) => child !== undefined)
        .map(([key, child]) => JSON.stringify(key) + ":" + encode(child))
        .join(",") +
      "}"
    );
  };
  return utf8.encode(encode(value));
}
export function equal(a, b) {
  if (a === b) return true;
  if (a === null || b === null || typeof a !== typeof b) return false;
  if (Array.isArray(a))
    return (
      Array.isArray(b) &&
      a.length === b.length &&
      a.every((v, i) => equal(v, b[i]))
    );
  if (typeof a === "object") {
    const ks = Object.keys(a);
    return (
      ks.length === Object.keys(b).length &&
      ks.every((k) => Object.hasOwn(b, k) && equal(a[k], b[k]))
    );
  }
  return false;
}
