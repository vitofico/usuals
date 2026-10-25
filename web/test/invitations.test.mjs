import { test } from "node:test";
import assert from "node:assert/strict";
import { invitationToken, InvitationsApi } from "../src/invitations.mjs";

test("invitation secrets are accepted only as a complete fragment on the invitation landing page", () => {
  const token = "a".repeat(43);
  assert.equal(invitationToken(new URL(`https://usuals.invalid/join#${token}`)), token);
  for (const url of [`https://usuals.invalid/#${token}`, `https://usuals.invalid/join?token=${token}`,
    `https://usuals.invalid/join#${token}&redirect=evil`, "https://usuals.invalid/join#invalid"]) {
    assert.equal(invitationToken(new URL(url)), null);
  }
});
test("capturing a link uses a browser challenge and never puts its secret into a request URL or membership request", async () => {
  const calls = [], token = "b".repeat(43);
  const api = new InvitationsApi({ csrf: () => "session-csrf", transport: async (path, options) => {
    calls.push({ path, options });
    if (path.endsWith("challenge")) return { csrfToken: "challenge-csrf" };
    if (path.endsWith("accept")) return { list: { id: "target-list" } };
    return {};
  } });
  await api.capture(token);
  assert.equal(calls[1].options.csrf, "challenge-csrf");
  assert.deepEqual(calls[1].options.body, { token });
  assert.ok(calls.every(({ path }) => !path.includes(token)));
  assert.ok(calls.every(({ path }) => !path.endsWith("accept")));
  assert.equal((await api.accept()).list.id, "target-list");
  assert.equal(calls[2].options.csrf, "session-csrf");
  assert.deepEqual(calls[2].options.body, {});
});
