import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { b64, unb64, hash } from "../src/wire.mjs";
import { admitKey, macKey, joinMac, verifyJoinMac, joinCode, descriptorAnchor, inviteLink, appLink, parseInviteLink,
  connectionCode } from "../src/pairing.mjs";

const v = JSON.parse(await readFile(new URL("../../testing/src/test/resources/pairing-v2/vectors.json", import.meta.url), "utf8"));
const key = unb64(v.key, 32), assertion = unb64(v.assertion);

test("pairing v2 keys, MAC and code match the Python golden", async () => {
  const admit = await admitKey(key), mac = await macKey(key);
  assert.equal(b64(admit), v.admit);
  assert.equal(await hash(admit), v.secretHash);
  assert.equal(b64(mac), v.macKey);
  assert.equal(await joinMac(mac, assertion), v.mac);
  assert.equal(await joinCode(mac, assertion), v.code);
  assert.equal(await verifyJoinMac(mac, assertion, v.mac), true);
  const changed = assertion.slice(); changed[0] ^= 1;
  assert.equal(await verifyJoinMac(mac, changed, v.mac), false);
  assert.equal(await verifyJoinMac(mac, assertion, "not-a-mac"), false);
});

test("pairing v2 anchor and links match the golden and round-trip", async () => {
  const anchor = await descriptorAnchor(unb64(v.descriptor));
  assert.equal(b64(anchor), v.anchor);
  assert.equal(inviteLink(v.origin, v.inviteId, key, anchor), v.link);
  assert.equal(appLink(v.link), v.appLink);
  for (const text of [v.link, v.appLink]) {
    const parsed = parseInviteLink(text);
    assert.equal(parsed.origin, v.origin);
    assert.equal(parsed.inviteId, v.inviteId);
    assert.equal(b64(parsed.key), v.key);
    assert.equal(b64(parsed.anchor), v.anchor);
  }
});

test("legacy JSON invitations ask for an update and malformed links are rejected", () => {
  assert.throws(() => parseInviteLink("usuals://join#" + b64(new TextEncoder().encode('{"version":1}'))), { code: "upgrade" });
  const payload = v.link.slice(v.link.indexOf("#2.") + 3);
  for (const bad of ["https://usuals.example/join#1.AAAA", "https://usuals.example/join", "https://usuals.example/other#2." + payload,
    v.link.slice(0, -2), "https://user@usuals.example/join#2." + payload, "http://usuals.example/join#2." + payload])
    assert.throws(() => parseInviteLink(bad));
});

test("connection codes are six digits from the fingerprint prefix", () => {
  for (const [fingerprint, expected] of v.connectionCodes) assert.equal(connectionCode(fingerprint), expected);
});
