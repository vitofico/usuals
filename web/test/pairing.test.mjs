import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { b64, unb64, hash, hex, fingerprint } from "../src/wire.mjs";
import { admitKey, macKey, joinMac, verifyJoinMac, joinCode, descriptorAnchor, inviteLink, appLink, parseInviteLink,
  connectionCode, resumableInvite, forgetInvite, INVITE_RESUME_WINDOW, connectLink, connectAppLink, parseConnectLink, joinsConnection } from "../src/pairing.mjs";

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

test("connect links match the golden and carry the optional invite", () => {
  const secret = unb64(v.offerSecret, 32), invite = { inviteId: v.inviteId, key, anchor: unb64(v.anchor, 16) };
  assert.equal(connectLink(v.origin, v.offerId, secret), v.connectLink);
  assert.equal(connectLink(v.origin, v.offerId, secret, invite), v.connectInviteLink);
  assert.equal(connectAppLink(v.connectLink), v.connectAppLink);
  assert.equal(connectAppLink(v.connectInviteLink), v.connectInviteAppLink);
  const plain = parseConnectLink(v.connectLink);
  assert.deepEqual([plain.origin, plain.offerId, b64(plain.secret), plain.invite], [v.origin, v.offerId, v.offerSecret, null]);
  const full = parseConnectLink(v.connectInviteLink);
  assert.equal(inviteLink(full.origin, full.invite.inviteId, full.invite.key, full.invite.anchor), v.link);
  const payload = v.connectLink.slice(v.connectLink.indexOf("#1.") + 3);
  for (const bad of ["https://usuals.example/connect#2." + payload, "https://usuals.example/connect", "https://usuals.example/join#1." + payload,
    v.connectLink.slice(0, -2), "https://user@usuals.example/connect#1." + payload, "http://usuals.example/connect#1." + payload])
    assert.throws(() => parseConnectLink(bad));
});

test("connection codes are six digits from the fingerprint prefix", () => {
  for (const [fingerprint, expected] of v.connectionCodes) assert.equal(connectionCode(fingerprint), expected);
});

test("only the device whose connection code was compared joins without a second comparison", async () => {
  const key = () => b64(crypto.getRandomValues(new Uint8Array(32)));
  const server = key(), phone = { signingPublicKey: key(), recipientPublicKey: key() }, ticket = { id: crypto.randomUUID() };
  ticket.fingerprint = hex(unb64(await fingerprint(server, ticket.id, phone), 32));
  assert.equal(await joinsConnection(ticket, server, phone), true);
  assert.equal(await joinsConnection(ticket, server, { ...phone, recipientPublicKey: key() }), false, "a photographed QR's invite with other keys");
  assert.equal(await joinsConnection({ ...ticket, id: crypto.randomUUID() }, server, phone), false);
});

const tab = () => {
  const values = new Map();
  return { values, getItem: (k) => values.get(k) ?? null, setItem: (k, value) => values.set(k, String(value)), removeItem: (k) => values.delete(k) };
};
const opened = () => inviteLink("https://usuals.example", "00000000-0000-4000-8000-000000000001", key, new Uint8Array(16));

test("a /join link survives the sign-in redirect in the same tab", () => {
  const storage = tab(), link = opened();
  assert.equal(resumableInvite(storage, link, 1_000), link);
  assert.equal(resumableInvite(storage, null, 1_000 + INVITE_RESUME_WINDOW - 1), link);
});

test("a kept invite is forgotten once the resume window has passed", () => {
  const storage = tab();
  resumableInvite(storage, opened(), 1_000);
  assert.equal(resumableInvite(storage, null, 1_000 + INVITE_RESUME_WINDOW), null);
  assert.equal(storage.values.size, 0);
});

test("using or dismissing the invite removes its key from the tab", () => {
  const storage = tab();
  resumableInvite(storage, opened(), 1_000);
  forgetInvite(storage);
  assert.equal(storage.values.size, 0);
  assert.equal(resumableInvite(storage, null, 2_000), null);
});

test("a damaged kept invite is discarded and blocked storage still offers a fresh link", () => {
  for (const damaged of ["{", JSON.stringify({ link: "https://usuals.example/join#2.short", at: 1_000 }), JSON.stringify({ link: opened(), at: 5_000 })]) {
    const storage = tab();
    resumableInvite(storage, opened(), 1_000);
    for (const k of storage.values.keys()) storage.values.set(k, damaged);
    assert.equal(resumableInvite(storage, null, 2_000), null);
    assert.equal(storage.values.size, 0);
  }
  const blocked = { getItem() { throw new Error("blocked"); }, setItem() { throw new Error("blocked"); }, removeItem() { throw new Error("blocked"); } };
  assert.equal(resumableInvite(blocked, opened(), 1_000), opened());
  assert.equal(resumableInvite(blocked, null, 1_000), null);
  assert.equal(resumableInvite(null, null, 1_000), null);
});

