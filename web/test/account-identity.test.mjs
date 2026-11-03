import test from "node:test";
import assert from "node:assert/strict";
import { accountName, accountLogin } from "../src/account-identity.mjs";

test("identity prefers the display name and keeps the login available", () => {
  const user = { displayName: " Vito ", username: "vito", email: "vito@example.org" };
  assert.equal(accountName(user), "Vito");
  assert.equal(accountLogin(user), "vito");
});

test("blank names fall back to username or email and never an opaque identity", () => {
  assert.equal(accountName({ displayName: " \t", username: " vito " }), "vito");
  assert.equal(accountName({ displayName: "", username: " ", email: "vito@example.org" }), "vito@example.org");
  assert.equal(accountName({ subject: "00000000-0000-4000-8000-000000000001" }), null);
  assert.equal(accountName(null), null);
  assert.equal(accountLogin({ displayName: "Vito" }), null);
});
