import test from "node:test";
import assert from "node:assert/strict";
import { ServerModel } from "../src/server-model.mjs";

test("server model sends fixed same-origin operations with account CSRF and explicit revision", async () => {
  const calls = [];
  const requester = async (path, options) => {
    calls.push([path, options]);
    if (path.endsWith("settings")) return { selected: "local", revision: "accepted", dailyLimit: 20, used: 0 };
    if (path.endsWith("probe-image")) return { images: true, structured: true };
    return { items: [{ name: "milk", amount: "1.5", unit: "LITRE", note: null }] };
  };
  const model = new ServerModel({ csrf: "account-csrf", requester });
  await assert.rejects(model.read("data:image/jpeg;base64,AQ=="));
  await model.settings();
  await model.check();
  const items = await model.read("data:image/jpeg;base64,AQ==");
  assert.equal(items[0].quantity, 1.5);
  assert.equal(calls.at(-1)[0], "/v1/ai/image");
  assert.deepEqual(calls.at(-1)[1].body, { image: "AQ==", mimeType: "image/jpeg", revision: "accepted" });
  assert.equal(calls.at(-1)[1].csrf, "account-csrf");
  assert.equal(JSON.stringify(calls).includes("Authorization"), false);
  model.disconnect();
  assert.equal(model.status.verified, false);
});

test("server text results stay drafts and closing the dialog rejects late completion", async () => {
  let complete;
  const model = new ServerModel({ csrf: "test", requester: async (path) => {
    if (path.endsWith("settings")) return { selected: "local", revision: "r1", dailyLimit: 20, used: 0 };
    return new Promise(resolve => { complete = resolve; });
  } });
  await model.settings();
  const pending = model.text("recipe source");
  model.disconnect();
  complete({ items: [{ name: "bread" }] });
  await assert.rejects(pending);
});

test("failed image check never enables upload and invalid quantities are rejected", async () => {
  const model = new ServerModel({ csrf: "test", requester: async path => path.endsWith("settings")
    ? { selected: "local", revision: "r1", dailyLimit: 2, used: 0 } : { images: false, structured: true } });
  await model.settings();
  await assert.rejects(model.check());
  assert.equal(model.status.verified, false);
  await assert.rejects(model.read("data:image/jpeg;base64,AQ=="));
  const invalid = new ServerModel({ csrf: "test", requester: async path => path.endsWith("settings")
    ? { selected: "local", revision: "r1", dailyLimit: 2, used: 0 } : { items: [{ name: "milk", amount: "-1", unit: "LITRE" }] } });
  await invalid.settings(); await assert.rejects(invalid.text("milk"));
});


test("production requester accepts the gateway deadline for settings and extraction", async () => {
  const original = globalThis.fetch, paths = [];
  globalThis.fetch = async (path, options) => {
    paths.push(path);
    assert.equal(options.headers["X-CSRF-Token"], "account-csrf");
    const reply = path.endsWith("settings") ? { selected: "local", revision: "r1", dailyLimit: 20, used: 0 }
      : path.endsWith("probe-image") ? { images: true, structured: true }
      : { items: [{ name: "milk", amount: "1.5", unit: "LITRE" }] };
    return new Response(JSON.stringify(reply), { status: 200, headers: { "Content-Type": "application/json" } });
  };
  try {
    const model = new ServerModel({ csrf: "account-csrf" });
    await model.settings(); await model.check();
    assert.equal((await model.text("milk")).length, 1);
    assert.deepEqual(paths, ["/v1/ai/settings", "/v1/ai/probe-image", "/v1/ai/text"]);
    model.disconnect();
  } finally { globalThis.fetch = original; }
});
