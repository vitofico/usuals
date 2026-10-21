import test from "node:test";
import assert from "node:assert/strict";
import { b64, unb64 } from "../src/wire.mjs";
import { connectLink, connectAppLink, parseConnectLink, connectionCode } from "../src/connect.mjs";

// The same vector the phone's parser is tested against (sync PairingTest).
const golden = {
  origin: "https://usuals.example",
  offerId: "00000000-0000-4000-8000-000000000002",
  secret: "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8",
  link: "https://usuals.example/connect#1.AAAAAAAAQACAAAAAAAAAAiAhIiMkJSYnKCkqKywtLi8wMTIzNDU2Nzg5Ojs8PT4_",
  appLink: "usuals://connect#aHR0cHM6Ly91c3VhbHMuZXhhbXBsZS9jb25uZWN0IzEuQUFBQUFBQUFRQUNBQUFBQUFBQUFBaUFoSWlNa0pTWW5LQ2txS3l3dExpOHdNVEl6TkRVMk56ZzVPanM4UFQ0Xw",
};

test("the connect QR carries exactly the 16-byte offer id and the 32-byte secret", () => {
  const link = connectLink(golden.origin, golden.offerId, unb64(golden.secret, 32));
  assert.equal(link, golden.link);
  assert.equal(unb64(link.slice(link.indexOf("#1.") + 3)).length, 48);
  assert.equal(connectAppLink(link), golden.appLink);
  const parsed = parseConnectLink(link);
  assert.deepEqual([parsed.origin, parsed.offerId, b64(parsed.secret)], [golden.origin, golden.offerId, golden.secret]);
  assert.equal(connectLink("http://localhost:8080", golden.offerId, new Uint8Array(32)).startsWith("http://localhost:8080/connect#1."), true);
});

test("household invite bytes, other versions and foreign or unsafe origins are refused", () => {
  const payload = golden.link.slice(golden.link.indexOf("#1.") + 3);
  // The retired one-scan form that also carried a household invite.
  const withInvite = golden.link + "AAAAAAAAQACAAAAAAAAAAQABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhscHR4f5Uq_XQZOZryKvn_Texd6nQ";
  for (const bad of [withInvite, "https://usuals.example/connect#2." + payload, "https://usuals.example/connect", "https://usuals.example/join#1." + payload,
    golden.link.slice(0, -2), "https://user@usuals.example/connect#1." + payload, "http://usuals.example/connect#1." + payload])
    assert.throws(() => parseConnectLink(bad), bad);
  assert.throws(() => connectLink(golden.origin, golden.offerId, new Uint8Array(31)));
  assert.throws(() => connectLink("https://usuals.example/path", golden.offerId, new Uint8Array(32)));
});

test("a phone signing in through this browser shows six digits from its fingerprint", () => {
  for (const [fingerprint, expected] of [["0".repeat(64), "000000"], ["f".repeat(64), "967295"], ["0001e240" + "0".repeat(56), "123456"]])
    assert.equal(connectionCode(fingerprint), expected);
  assert.throws(() => connectionCode("F".repeat(64)));
});
