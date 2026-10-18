import {
  bytes,
  b64,
  unb64,
  uint,
  field,
  domain,
  digest,
  hash,
  requireThat,
  encodeHeader,
  parseHeader,
  signingBytes,
  head,
  receiptBytes,
  packageContext,
  packageBytes,
  fingerprint,
} from "./wire.mjs";
const subtle = () => crypto.subtle;
const prefix = {
  Ed25519: "302e020100300506032b657004220420",
  X25519: "302e020100300506032b656e04220420",
};
const fromHex = (s) => Uint8Array.from(s.match(/../g), (v) => parseInt(v, 16));
export async function privateKey(type, raw, usage) {
  return subtle().importKey(
    "pkcs8",
    bytes(fromHex(prefix[type]), raw),
    { name: type },
    false,
    usage,
  );
}
export async function sign(device, input) {
  return b64(
    new Uint8Array(
      await subtle().sign(
        "Ed25519",
        await privateKey("Ed25519", unb64(device.signingSeed, 32), ["sign"]),
        input,
      ),
    ),
  );
}
export async function verify(publicKey, signature, input) {
  const key = await subtle().importKey(
    "raw",
    unb64(publicKey, 32),
    { name: "Ed25519" },
    false,
    ["verify"],
  );
  requireThat(
    await subtle().verify("Ed25519", key, unb64(signature, 64), input),
    "unverified",
  );
}
export async function newDevice() {
  const signing = await subtle().generateKey("Ed25519", true, [
      "sign",
      "verify",
    ]),
    recipient = await subtle().generateKey("X25519", true, ["deriveBits"]);
  return {
    publicKeys: {
      id: crypto.randomUUID(),
      signingPublicKey: b64(
        new Uint8Array(await subtle().exportKey("raw", signing.publicKey)),
      ),
      recipientPublicKey: b64(
        new Uint8Array(await subtle().exportKey("raw", recipient.publicKey)),
      ),
    },
    signingSeed: b64(
      new Uint8Array(
        await subtle().exportKey("pkcs8", signing.privateKey),
      ).slice(-32),
    ),
    recipientSecret: b64(
      new Uint8Array(
        await subtle().exportKey("pkcs8", recipient.privateKey),
      ).slice(-32),
    ),
  };
}
export function random(size) {
  return crypto.getRandomValues(new Uint8Array(size));
}
export async function aesEncrypt(key, plaintext, aad, nonce = random(12)) {
  const k = await subtle().importKey("raw", key, "AES-GCM", false, ["encrypt"]);
  return bytes(
    nonce,
    new Uint8Array(
      await subtle().encrypt(
        { name: "AES-GCM", iv: nonce, additionalData: aad, tagLength: 128 },
        k,
        plaintext,
      ),
    ),
  );
}
export async function aesDecrypt(key, ciphertext, aad) {
  requireThat(ciphertext.length >= 28);
  const k = await subtle().importKey("raw", key, "AES-GCM", false, ["decrypt"]);
  return new Uint8Array(
    await subtle().decrypt(
      {
        name: "AES-GCM",
        iv: ciphertext.slice(0, 12),
        additionalData: aad,
        tagLength: 128,
      },
      k,
      ciphertext.slice(12),
    ),
  );
}
export function validateEnvelope(e, max = 65536) {
  parseHeader(e.header);
  requireThat(
    unb64(e.ciphertext).length >= 28 && unb64(e.ciphertext).length + 260 <= max,
  );
  unb64(e.signature, 64);
}
export async function seal(
  header,
  plaintext,
  key,
  device,
  reserve,
  max = 65536,
) {
  requireThat(
    plaintext.length + 288 <= max &&
      key.length === 32 &&
      header.authorDeviceId === device.publicKeys.id,
  );
  const h = encodeHeader(header);
  await reserve();
  const e = {
    header: b64(h),
    ciphertext: b64(await aesEncrypt(key, plaintext, domain("AAD", h))),
  };
  e.signature = await sign(device, signingBytes(e));
  validateEnvelope(e, max);
  return e;
}
export async function open(e, key, author, max = 65536) {
  validateEnvelope(e, max);
  await verify(author, e.signature, signingBytes(e));
  return aesDecrypt(
    key,
    unb64(e.ciphertext),
    domain("AAD", unb64(e.header, 196)),
  );
}
export async function verifyReceipt(e, r, server, next, previous) {
  const h = parseHeader(e.header);
  requireThat(
    (await hash(unb64(server, 32))) === h.serverId &&
      r.seq === next &&
      r.previousHead === previous &&
      (await head(previous, next, e)) === r.head,
    "unverified",
  );
  await verify(
    server,
    r.signature,
    receiptBytes(h.serverId, h.householdId, h.listId, r.seq, r.head),
  );
}
async function hkdfExtract(salt, input) {
  const key = await subtle().importKey(
    "raw",
    salt.length ? salt : new Uint8Array(32),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  return new Uint8Array(await subtle().sign("HMAC", key, input));
}
async function hkdfExpand(prk, info, length) {
  requireThat(length <= 255 * 32);
  let previous = new Uint8Array(),
    out = new Uint8Array();
  const k = await subtle().importKey(
    "raw",
    prk,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  for (let i = 1; out.length < length; i++) {
    previous = new Uint8Array(
      await subtle().sign(
        "HMAC",
        k,
        bytes(previous, info, new Uint8Array([i])),
      ),
    );
    out = bytes(out, previous);
  }
  return out.slice(0, length);
}
const kemSuite = bytes("KEM", uint(0x20, 2)),
  suite = bytes("HPKE", uint(0x20, 2), uint(1, 2), uint(2, 2));
async function labeledExtract(salt, id, label, input) {
  return hkdfExtract(salt, bytes("HPKE-v1", id, label, input));
}
async function labeledExpand(prk, id, label, info, length) {
  return hkdfExpand(
    prk,
    bytes(uint(length, 2), "HPKE-v1", id, label, info),
    length,
  );
}
async function sharedSecret(secret, publicKey, enc, recipient) {
  const sk = await privateKey("X25519", secret, ["deriveBits"]),
    pk = await subtle().importKey(
      "raw",
      publicKey,
      { name: "X25519" },
      false,
      [],
    );
  const dh = new Uint8Array(
    await subtle().deriveBits({ name: "X25519", public: pk }, sk, 256),
  );
  requireThat(dh.some((x) => x !== 0));
  const prk = await labeledExtract(new Uint8Array(), kemSuite, "eae_prk", dh);
  return labeledExpand(
    prk,
    kemSuite,
    "shared_secret",
    bytes(enc, recipient),
    32,
  );
}
async function schedule(shared, info) {
  const empty = new Uint8Array(),
    pskIdHash = await labeledExtract(empty, suite, "psk_id_hash", empty),
    infoHash = await labeledExtract(empty, suite, "info_hash", info),
    context = bytes(new Uint8Array([0]), pskIdHash, infoHash),
    secret = await labeledExtract(shared, suite, "secret", empty);
  return {
    key: await labeledExpand(secret, suite, "key", context, 32),
    nonce: await labeledExpand(secret, suite, "base_nonce", context, 12),
  };
}
export async function hpkeOpen(secret, recipient, ciphertext, info) {
  requireThat(ciphertext.length >= 48);
  const enc = ciphertext.slice(0, 32),
    s = await schedule(await sharedSecret(secret, enc, enc, recipient), info);
  return aesDecrypt(
    s.key,
    bytes(s.nonce, ciphertext.slice(32)),
    new Uint8Array(),
  );
}
export async function hpkeSeal(recipient, plaintext, info) {
  const ephemeral = await subtle().generateKey("X25519", true, ["deriveBits"]),
    enc = new Uint8Array(await subtle().exportKey("raw", ephemeral.publicKey)),
    secret = new Uint8Array(
      await subtle().exportKey("pkcs8", ephemeral.privateKey),
    ).slice(-32),
    s = await schedule(
      await sharedSecret(secret, recipient, enc, recipient),
      info,
    );
  return bytes(
    enc,
    (await aesEncrypt(s.key, plaintext, new Uint8Array(), s.nonce)).slice(12),
  );
}
export async function unwrap(server, household, p, device, author) {
  requireThat(
    p.recipientFingerprint ===
      (await fingerprint(server, household, device.publicKeys)),
  );
  await verify(author, p.signature, packageBytes(server, household, p));
  const plain = await hpkeOpen(
    unb64(device.recipientSecret, 32),
    unb64(device.publicKeys.recipientPublicKey, 32),
    unb64(p.ciphertext),
    packageContext(server, household, p),
  );
  requireThat(plain.length === 32);
  return plain;
}
