import test from "node:test";
import assert from "node:assert/strict";
import { forgetLegacyVault } from "../src/legacy.mjs";

const storage = (entries) => {
  const values = new Map(entries);
  return { values, removeItem: (key) => values.delete(key) };
};

test("the old browser vault and its keys are deleted while preferences stay", () => {
  const deleted = [], local = storage([["usuals-tutorial-v1", "complete"], ["usuals-language", "it"], ["usuals-palette", "tide"]]);
  const tab = storage([["usuals-join-invite", "{}"], ["usuals-connect-request", "ticket"]]);
  forgetLegacyVault({ indexedDB: { deleteDatabase: (name) => deleted.push(name) }, localStorage: local, sessionStorage: tab });
  assert.deepEqual(deleted, ["usuals-private-vault"]);
  assert.deepEqual([...local.values.keys()], ["usuals-language", "usuals-palette"]);
  assert.deepEqual([...tab.values.keys()], ["usuals-connect-request"], "a phone's pending sign-in ticket is not part of the vault");
});

test("missing or blocked storage never stops the app from starting", () => {
  const blocked = { deleteDatabase() { throw new Error("blocked"); }, removeItem() { throw new Error("blocked"); } };
  assert.doesNotThrow(() => forgetLegacyVault({}));
  assert.doesNotThrow(() => forgetLegacyVault({ indexedDB: blocked, localStorage: blocked, sessionStorage: blocked }));
  const throwing = { get indexedDB() { throw new Error("SecurityError"); }, get localStorage() { throw new Error("SecurityError"); } };
  assert.doesNotThrow(() => forgetLegacyVault(throwing));
});
