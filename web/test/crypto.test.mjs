import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import {
  b64,
  unb64,
  hex,
  encodeHeader,
  parseHeader,
  domain,
  signingBytes,
  digest,
  hash,
  strictJson,
  jsonBytes,
  zero,
  authBytes,
} from "../src/wire.mjs";
import {
  newDevice,
  sign,
  verify,
  hpkeSeal,
  hpkeOpen,
  seal,
  open,
  random,
} from "../src/crypto.mjs";
const fromHex = (s) =>
  Uint8Array.from(s.match(/../g) ?? [], (v) => parseInt(v, 16));
test("independent big-endian header and signing input golden", async () => {
  const f = JSON.parse(
      await readFile(
        new URL(
          "../../testing/src/test/resources/shared-list-v1/wire.json",
          import.meta.url,
        ),
        "utf8",
      ),
    ),
    h = {
      serverId: b64(fromHex(f.serverId)),
      householdId: f.uuids[0],
      listId: f.uuids[1],
      authorDeviceId: f.uuids[2],
      intentId: f.uuids[3],
      envelopeId: f.uuids[4],
      epoch: f.epoch,
      manifestHash: b64(new Uint8Array(32).fill(f.manifestFillByte)),
      baseSeq: f.baseSeq,
      baseHead: b64(new Uint8Array(32).fill(f.baseHeadFillByte)),
    };
  assert.equal(hex(encodeHeader(h)), f.headerHex);
  const e = {
    header: b64(encodeHeader(h)),
    ciphertext: b64(fromHex(f.ciphertextHex)),
  };
  assert.equal(hex(domain("AAD", encodeHeader(h))), f.aadHex);
  assert.equal(hex(signingBytes(e)), f.signingInputHex);
  assert.equal(hex(await digest(signingBytes(e))), f.signingInputSha256);
  assert.equal(parseHeader(e.header).baseSeq, 7);
});
test("RFC 8032 Ed25519 known vector and wrong message rejection", async () => {
  const seed =
      "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
    pub = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
    signature =
      "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b";
  const signed = await sign(
    { signingSeed: b64(fromHex(seed)) },
    new Uint8Array(),
  );
  assert.equal(hex(unb64(signed)), signature);
  await verify(b64(fromHex(pub)), signed, new Uint8Array());
  await assert.rejects(verify(b64(fromHex(pub)), signed, new Uint8Array([1])));
});
test("HPKE roundtrip, tampering, wrong context, wrong key and small-order rejection", async () => {
  const d = await newDevice(),
    recipient = unb64(d.publicKeys.recipientPublicKey, 32),
    plaintext = random(32),
    info = jsonBytes({ context: "synthetic" }),
    cipher = await hpkeSeal(recipient, plaintext, info);
  assert.deepEqual(
    await hpkeOpen(unb64(d.recipientSecret, 32), recipient, cipher, info),
    plaintext,
  );
  const tampered = cipher.slice();
  tampered[tampered.length - 1] ^= 1;
  await assert.rejects(
    hpkeOpen(unb64(d.recipientSecret, 32), recipient, tampered, info),
  );
  await assert.rejects(
    hpkeOpen(
      unb64(d.recipientSecret, 32),
      recipient,
      cipher,
      new Uint8Array([1]),
    ),
  );
  const other = await newDevice();
  await assert.rejects(
    hpkeOpen(
      unb64(other.recipientSecret, 32),
      unb64(other.publicKeys.recipientPublicKey, 32),
      cipher,
      info,
    ),
  );
  await assert.rejects(hpkeSeal(new Uint8Array(32), plaintext, info));
});
test("AES envelope reserves before encryption and binds its exact header", async () => {
  const d = await newDevice(),
    key = random(32),
    h = {
      serverId: b64(random(32)),
      householdId: crypto.randomUUID(),
      listId: crypto.randomUUID(),
      authorDeviceId: d.publicKeys.id,
      intentId: crypto.randomUUID(),
      envelopeId: crypto.randomUUID(),
      epoch: 1,
      manifestHash: zero,
      baseSeq: 0,
      baseHead: zero,
    },
    plain = jsonBytes({ test: "value" });
  let count = 0;
  const e = await seal(h, plain, key, d, async () => {
    count++;
  });
  assert.equal(count, 1);
  assert.deepEqual(await open(e, key, d.publicKeys.signingPublicKey), plain);
  const changed = {
    ...e,
    header: b64(encodeHeader({ ...h, listId: crypto.randomUUID() })),
  };
  await assert.rejects(open(changed, key, d.publicKeys.signingPublicKey));
  await assert.rejects(
    seal(h, plain, key, d, async () => {
      throw new Error("storage");
    }),
  );
  assert.equal(count, 1);
});
test("strict JSON rejects malformed UTF8, duplicate decoded keys, unsafe integers and prototype keys", () => {
  for (const input of [
    '{"a":1,"a":2}',
    '{"a":1,"\\u0061":2}',
    '{"\\u0000aurea.number":"1.0"}',
    '{"x":9007199254740992}',
    '{"x":null}',
    "[1,]",
    "true false",
  ])
    assert.throws(() => strictJson(input));
  assert.throws(() => strictJson(new Uint8Array([0xc0, 0xaf])));
  assert.deepEqual(strictJson('{"x":null}', 1024, true), { x: null });
  assert.throws(() => strictJson("[".repeat(34) + "0" + "]".repeat(34)));
});
test("base64 and header reject noncanonical forms and unsupported ranges", () => {
  assert.throws(() => unb64("a="));
  assert.throws(() => unb64("AB"));
  const header = fromHex("0001" + "00".repeat(194));
  assert.throws(() => parseHeader(b64(header)));
  assert.equal(unb64(zero, 32).length, 32);
});

test("numeric lexemes retain JVM JsonPrimitive identity through copies and wire bytes", async () => {
  const { primitive, number, equal, text } = await import("../src/wire.mjs"),
    parsed = strictJson(
      '{"plain":1,"double":1.0,"exponent":1e0,"negativeZero":-0.0}',
      1024,
      true,
    );
  assert.equal(primitive(parsed.double), "1.0");
  assert.equal(number(parsed.double), 1);
  assert.equal(equal(parsed.plain, parsed.double), false);
  assert.equal(equal(parsed.double, parsed.exponent), false);
  assert.equal(
    text.decode(jsonBytes(structuredClone(parsed))),
    '{"plain":1,"double":1.0,"exponent":1e0,"negativeZero":-0.0}',
  );
  const special = strictJson(
    '{"__proto__":{"polluted":true},"constructor":4}',
    1024,
    true,
  );
  assert.equal(Object.hasOwn(special, "__proto__"), true);
  assert.equal({}.polluted, undefined);
  assert.equal(special.constructor, 4);
});

test("quoted signed decimal integers preserve Kotlin parsing and deny overflow", async () => {
  const { strictInteger } = await import("../src/wire.mjs");
  assert.equal(strictInteger("01"), 1);
  assert.equal(strictInteger("+1"), 1);
  assert.equal(strictInteger("-0"), 0);
  for (const value of [
    "1.0",
    "1e0",
    " 1",
    "9223372036854775808",
    "-9223372036854775809",
  ])
    assert.throws(() => strictInteger(value));
  assert.throws(
    () => strictInteger("9007199254740992"),
    (error) => error.code === "upgrade_required",
  );
});
