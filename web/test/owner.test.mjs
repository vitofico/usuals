import test from "node:test";
import assert from "node:assert/strict";
import { HouseholdClient } from "../src/client.mjs";
import { prepareHousehold, verifySavedHousehold } from "../src/recovery.mjs";
import { newDevice, random, unwrap, sign } from "../src/crypto.mjs";
import { b64, hash, unb64, Problem, parseHeader, envelopeHash, jsonBytes, manifestHash, manifestBytes, joinBytes, parseJoin, fingerprint } from "../src/wire.mjs";
import { body, transition } from "../src/membership.mjs";
import { vaultInitial } from "../src/vault.mjs";
import { writeFile } from "node:fs/promises";
import { join } from "node:path";

const now = 1_800_000_000_000;
async function fixture() {
  const identity = await newDevice(), server = { serverId: await hash(unb64(identity.publicKeys.signingPublicKey)),
    receiptPublicKey: identity.publicKeys.signingPublicKey, protocolVersions: [1] };
  const origin = "https://usuals.invalid", draft = await prepareHousehold(origin, server);
  let state = vaultInitial();
  const vault = { read: () => structuredClone(state),
    mutate: async action => { const next = structuredClone(state); action(next); next.writes++; state = next; },
    reserve: async key => { state.reservations[key] = (state.reservations[key] ?? 0) + 1; } };
  const remote = { proofs: [], snapshots: {}, submissions: [], requests: [], fail: null, now };
  const transport = async (path, options = {}) => {
    if (path === "/v1/server-info") return server;
    if (path === "/v1/devices/challenge") return { challengeId: crypto.randomUUID(), nonce: b64(random(32)),
      serverId: server.serverId, expiresAt: now + 300000, purpose: options.body.purpose };
    if (path === "/api/households") {
      if (remote.fail === "quota") { remote.fail = null; throw new Problem("quota_exceeded"); }
      assert.ok(vault.read().creating, "persist owner before network I/O");
      const { manifestBytes, signature } = options.body.manifest, proof = { manifestBytes, signature };
      if (remote.proofs.length) assert.deepEqual(proof, remote.proofs[0]); else remote.proofs.push(proof);
      if (remote.fail === "creation") { remote.fail = null; throw new Problem("unavailable"); }
      return proof;
    }
    if (path === "/v1/devices/session") return { token: "synthetic", expiresAt: now + 3600000 };
    if (path === "/v1/join-requests") { const assertion = parseJoin(unb64(options.body.assertion));
      return { requestId: assertion.requestId, expiresAt: assertion.expiresAt }; }
    if (path.endsWith("/expired")) {
      const a = parseJoin(unb64(options.body.assertion));
      if (a.expiresAt > remote.now) throw new Problem("membership_conflict");
      if (remote.fail === "expiry") { remote.fail = null; throw new Problem("unavailable"); }
      await remote.onExpiry?.();
      return { requestId: a.requestId, expiresAt: a.expiresAt };
    }
    const id = body(draft.proof).householdId;
    if (path.endsWith("/invites")) return remote.invitation = { inviteId: crypto.randomUUID(), secret: b64(random(32)), expiresAt: now + 600000 };
    if (path.endsWith("/join-requests")) return { requests: remote.requests };
    if (path.includes("/key-packages?")) {
      if (remote.fail === "packages") { remote.fail = null; throw new Problem("unavailable"); }
      return { packages: remote.lastSigned.packages.filter(p => p.recipientFingerprint === remote.ownerFingerprint) };
    }
    if (path.startsWith(`/v1/households/${id}/membership?`)) {
      const after = Number(path.split("=")[1]), proofs = remote.proofs.filter(p => body(p).epoch > after).slice(0, 1);
      return { manifests: proofs, nextEpoch: proofs.length ? body(proofs.at(-1)).epoch : after };
    }
    if (path.includes("/snapshot-candidates/")) {
      const candidateId = path.split("/").at(-1), e = options.body.envelope, h = parseHeader(e.header);
      const pending = vault.read().households[id].pendingMembership;
      assert.deepEqual(pending.snapshots[h.listId].envelope, e, "persist ciphertext before upload");
      remote.submissions.push(JSON.stringify(e)); remote.snapshots[candidateId] = options.body;
      if (remote.fail === "stage") { remote.fail = null; throw new Problem("unavailable"); }
      return { candidateId, listId: h.listId, seq: h.baseSeq, head: h.baseHead, sha256: await envelopeHash(e) };
    }
    if (path === `/v1/households/${id}/lists` || (path === `/v1/households/${id}/membership` && options.method === "POST")) {
      if (remote.fail === "beforeCommit") { remote.fail = null; throw new Problem("unavailable"); }
      const { manifestBytes, signature } = options.body, proof = { manifestBytes, signature };
      if (body(proof).action === "approve_join" && remote.invitation.expiresAt <= remote.now) throw new Problem("invalid");
      const old = remote.proofs.at(-1);
      await transition(proof, body(old), await manifestHash(old), await manifestHash(remote.proofs[0]), server.serverId);
      remote.proofs.push(proof);
      remote.lastSigned = options.body;
      if (remote.fail === "commit") { remote.fail = null; throw new Problem("unavailable"); }
      return { manifests: [proof], nextEpoch: body(proof).epoch };
    }
    if (path.endsWith("/snapshot")) {
      const list = body(remote.proofs.at(-1)).lists[0];
      return remote.snapshots[list.snapshotId];
    }
    if (path.includes("/operations?")) return { entries: [], nextCursor: 0, hasMore: false };
    throw new Error("Unexpected test route " + path);
  };
  const client = (clock = () => now) => new HouseholdClient(vault, { csrfToken: "synthetic-csrf" }, { origin, transport, clock });
  const setup = () => verifySavedHousehold(draft, draft.file, draft.secret);
  return { draft, vault, remote, client, setup, server };
}
test("web creation saves native recovery kit, validates read-back and keeps recovery secrets out of browser state", async () => {
  const f = await fixture();
  await assert.rejects(verifySavedHousehold(f.draft, f.draft.file, b64(random(32))));
  await assert.rejects(verifySavedHousehold(f.draft, jsonBytes({ version: 1, ciphertext: b64(random(100)) }), f.draft.secret));
  const c = f.client(), selected = await c.createHousehold(await f.setup(), "Our list");
  assert.equal(f.vault.read().creating, null);
  assert.equal(f.remote.proofs.length, 2);
  assert.equal(body(f.remote.proofs[1]).action, "create_list");
  await c.synchronize(selected.household, selected.list);
  assert.equal((await c.view(selected.household, selected.list)).name, "Our list");
  const saved = JSON.stringify(f.vault.read());
  assert.equal(saved.includes(f.draft.secret), false);
  assert.equal(saved.includes('"recovery":{"publicKeys"'), false);
  assert.deepEqual(Object.values(f.vault.read().reservations), [1]);
  if (process.env.USUALS_WEB_INTEROP_DIR) await writeFile(join(process.env.USUALS_WEB_INTEROP_DIR, "browser-owner-output.json"),
    JSON.stringify({ file: b64(f.draft.file), secret: f.draft.secret, signed: f.remote.lastSigned,
      snapshot: Object.values(f.remote.snapshots)[0], server: f.server }));
  c.stop();
});
for (const committed of [false, true]) test(`expired prepared approval resolves ${committed ? "a concurrent commit" : "an uncommitted request after cleanup"} without losing keys`, async () => {
  const f = await fixture(); let c = f.client();
  const selected = await c.createHousehold(await f.setup(), "Our list");
  await c.synchronize(selected.household, selected.list);
  const { parseInvitation } = await import("../src/client.mjs");
  const link = parseInvitation(await c.invite(selected.household), f.draft.origin), device = await newDevice();
  const a = { origin: f.draft.origin, serverId: f.server.serverId, householdId: selected.household,
    genesisHash: await manifestHash(f.draft.proof), inviteId: link.inviteId, requestId: crypto.randomUUID(),
    memberId: link.memberId, device: device.publicKeys, secretHash: await hash(unb64(link.secret)),
    challengeId: crypto.randomUUID(), nonce: b64(random(32)), expiresAt: link.expiresAt };
  const pending = { requestId: a.requestId, assertion: b64(joinBytes(a)), signature: await sign(device, joinBytes(a)) };
  f.remote.requests = [pending];
  const [applicant] = await c.applicants(selected.household); f.remote.fail = "beforeCommit";
  await assert.rejects(c.approve(selected.household, pending, applicant.fingerprint), e => e.code === "unavailable");
  const saved = c.read(selected.household).pendingMembership;
  await assert.rejects(c.discardInvalidMembership(selected.household), e => e.code === "membership_conflict");
  c.stop(); c = f.client(() => now + 700000); f.remote.now += 700000; f.remote.requests = [];
  await assert.rejects(c.refresh(selected.household), e => e.code === "invalid");
  f.remote.fail = "expiry";
  await assert.rejects(c.discardInvalidMembership(selected.household), e => e.code === "unavailable");
  assert.deepEqual(c.read(selected.household).pendingMembership, saved);
  if (committed) f.remote.onExpiry = async () => {
    const proof = { manifestBytes: saved.manifest.manifestBytes, signature: saved.manifest.signature };
    f.remote.proofs.push(proof); f.remote.lastSigned = saved.manifest;
  };
  assert.equal(await c.discardInvalidMembership(selected.household), !committed);
  assert.equal(c.read(selected.household).pendingMembership, null);
  if (committed) assert.equal(c.read(selected.household).keys[`${selected.list}:3`], saved.keys[selected.list]);
  else {
    assert.equal(c.read(selected.household).keys[`${selected.list}:3`], undefined);
    const { change } = await import("../src/owner.mjs");
    await change(c, selected.household, "rotate", body(c.read(selected.household).proofs.at(-1)).members);
  }
  await c.synchronize(selected.household, selected.list);
  assert.equal((await c.view(selected.household, selected.list)).name, "Our list"); c.stop();
});
test("web owner verifies native join framing and a full fingerprint before approving, then rotates removed-device keys", async () => {
  const f = await fixture(); let c = f.client();
  const selected = await c.createHousehold(await f.setup(), "Our list");
  await c.synchronize(selected.household, selected.list);
  const invitation = await c.invite(selected.household);
  const { parseInvitation } = await import("../src/client.mjs");
  const link = parseInvitation(invitation, f.draft.origin), device = await newDevice();
  const assertion = { origin: f.draft.origin, serverId: f.server.serverId, householdId: selected.household,
    genesisHash: await manifestHash(f.draft.proof), inviteId: link.inviteId, requestId: crypto.randomUUID(),
    memberId: link.memberId, device: device.publicKeys, secretHash: await hash(unb64(link.secret)),
    challengeId: crypto.randomUUID(), nonce: b64(random(32)), expiresAt: link.expiresAt };
  assert.deepEqual(parseJoin(joinBytes(assertion)), assertion);
  const bytes = joinBytes(assertion);
  for (let n = 0; n < bytes.length; n++) assert.throws(() => parseJoin(bytes.slice(0, n)));
  assert.throws(() => parseJoin(new Uint8Array([...bytes, 0])));
  const pending = { requestId: assertion.requestId, assertion: b64(bytes), signature: await sign(device, bytes) };
  f.remote.requests = [pending];
  const [applicant] = await c.applicants(selected.household);
  await assert.rejects(c.approve(selected.household, pending, "0".repeat(64)), e => e.code === "unverified");
  assert.equal(f.remote.proofs.length, 2);
  f.remote.fail = "commit";
  await assert.rejects(c.approve(selected.household, pending, applicant.fingerprint), e => e.code === "unavailable");
  c.stop(); c = f.client(() => now + 700000);
  await c.approve(selected.household, pending, applicant.fingerprint);
  assert.equal(f.remote.proofs.length, 3, "the committed approval resolves after reload and invitation expiry");
  assert.equal(c.read(selected.household).pendingMembership, null);
  const recipient = await fingerprint(f.server.serverId, selected.household, device.publicKeys);
  const key = await unwrap(f.server.serverId, selected.household, f.remote.lastSigned.packages.find(p => p.recipientFingerprint === recipient),
    device, f.draft.device.publicKeys.signingPublicKey);
  assert.deepEqual(key, unb64(c.read(selected.household).keys[`${selected.list}:3`]));
  f.remote.fail = "commit";
  await assert.rejects(c.removeDevice(selected.household, device.publicKeys.id), e => e.code === "unavailable");
  c.stop(); c = f.client(() => now + 700000);
  await c.removeDevice(selected.household, device.publicKeys.id);
  assert.equal(f.remote.proofs.length, 4, "the committed removal is not repeated");
  assert.equal(f.remote.lastSigned.packages.some(p => p.recipientFingerprint === recipient), false);
  assert.notEqual(c.read(selected.household).keys[`${selected.list}:4`], b64(key));
  assert.equal(body(f.remote.proofs.at(-1)).action, "remove");
  await assert.rejects(c.approve(selected.household, pending, applicant.fingerprint), e => e.code === "invite_expired");
  assert.equal(c.read(selected.household).pendingMembership, null);
  c.stop();
});
for (const phase of ["creation", "stage", "commit"]) test(`web owner resumes ${phase} interruption with the same identity, keys and ciphertext`, async () => {
  const f = await fixture(); f.remote.fail = phase;
  let c = f.client();
  await assert.rejects(c.createHousehold(await f.setup(), "Our list"), e => e.code === "unavailable");
  const identity = f.vault.read().creating.device;
  c.stop(); c = f.client();
  const selected = await c.completeCreation();
  assert.deepEqual(c.read(selected.household).device, identity);
  assert.equal(f.remote.proofs.length, 2);
  assert.deepEqual(Object.values(f.vault.read().reservations), [1]);
  if (phase === "stage") assert.equal(f.remote.submissions[0], f.remote.submissions[1]);
  await c.synchronize(selected.household, selected.list);
  assert.equal((await c.view(selected.household, selected.list)).name, "Our list");
  c.stop();
});
test("owner membership change preserves queued shopping edits instead of rotating over them", async () => {
  const f = await fixture(), c = f.client(), selected = await c.createHousehold(await f.setup(), "Our list");
  await c.synchronize(selected.household, selected.list);
  await c.enqueue(selected.household, selected.list, "capture", { proposedId: crypto.randomUUID(), key: "milk", name: "Milk" });
  const { change } = await import("../src/owner.mjs");
  await assert.rejects(change(c, selected.household, "rotate", body(c.read(selected.household).proofs.at(-1)).members), e => e.code === "pending_changes");
  assert.equal(c.read(selected.household).lists[selected.list].pending.length, 1);
  assert.equal(f.remote.proofs.length, 2); c.stop();
});
test("only a definitive quota rejection can abandon creation and allow joining an existing household", async () => {
  const f = await fixture(), c = f.client(); f.remote.fail = "quota";
  await assert.rejects(c.createHousehold(await f.setup(), "Our list"), e => e.code === "quota_exceeded");
  assert.equal(f.vault.read().creating.rejected, true);
  await c.abandonRejectedCreation();
  assert.equal(f.vault.read().creating, null);
  const owner = await newDevice(), invitation = { version: 1, origin: f.draft.origin, serverId: f.server.serverId,
    householdId: crypto.randomUUID(), genesisHash: b64(random(32)), ownerDeviceId: owner.publicKeys.id,
    ownerFingerprint: b64(random(32)), inviteId: crypto.randomUUID(), memberId: crypto.randomUUID(),
    role: "editor", secret: b64(random(32)), expiresAt: now + 600000 };
  await c.requestJoin("usuals://join#" + b64(jsonBytes(invitation)));
  assert.equal(f.vault.read().joining.invitation.householdId, invitation.householdId);
  c.stop();
});
test("retry clears quota abandonment permission before a potentially committed request", async () => {
  const f = await fixture(), c = f.client(); f.remote.fail = "quota";
  await assert.rejects(c.createHousehold(await f.setup(), "Our list"));
  f.remote.fail = "creation";
  await assert.rejects(c.completeCreation(), e => e.code === "unavailable");
  assert.equal(f.vault.read().creating.rejected, false);
  await assert.rejects(c.abandonRejectedCreation(), e => e.code === "membership_conflict");
  await c.completeCreation(); assert.equal(f.remote.proofs.length, 2); c.stop();
});
test("obsolete staging can be discarded after a newer verified checkpoint while retaining pending edits and pins", async () => {
  const f = await fixture(), c = f.client(), selected = await c.createHousehold(await f.setup(), "Our list");
  await c.synchronize(selected.household, selected.list);
  const { change } = await import("../src/owner.mjs"); f.remote.fail = "beforeCommit";
  await assert.rejects(change(c, selected.household, "rotate", body(c.read(selected.household).proofs.at(-1)).members));
  await assert.rejects(c.discardInvalidMembership(selected.household), e => e.code === "membership_conflict");
  const pending = c.read(selected.household).pendingMembership;
  await c.enqueue(selected.household, selected.list, "capture", { proposedId: crypto.randomUUID(), key: "milk", name: "Milk" });
  // Checkpoint verification is covered by the signed receipt/snapshot tests. Model its authenticated advance here.
  const projection = c.read(selected.household).lists[selected.list].confirmed.projection;
  c.checkpoint = async () => {
    await c.save(selected.household, h => { h.headPins[selected.list] = { seq: 1, head: b64(new Uint8Array(32).fill(9)) }; });
    return { seq: 1, head: b64(new Uint8Array(32).fill(9)), projection };
  };
  assert.equal(await c.discardInvalidMembership(selected.household), true);
  const h = c.read(selected.household);
  assert.equal(h.pendingMembership, null); assert.equal(h.lists[selected.list].pending.length, 1);
  assert.equal(h.headPins[selected.list].seq, 1); assert.equal(f.remote.proofs.length, 2);
  assert.ok(pending.manifest); c.stop();
});
test("discard resolves an ambiguous committed change instead of abandoning its epoch keys", async () => {
  const f = await fixture(), c = f.client(); f.remote.fail = "commit";
  await assert.rejects(c.createHousehold(await f.setup(), "Our list"));
  const id = body(f.draft.proof).householdId, pending = c.read(id).pendingMembership;
  assert.equal(await c.discardInvalidMembership(id), false);
  assert.equal(c.read(id).pendingMembership, null);
  assert.equal(c.read(id).keys[`${body(f.remote.proofs[1]).createdListId}:2`], Object.values(pending.keys)[0]);
  await c.completeCreation(); c.stop();
});
test("discard verifies a competing owner epoch before clearing obsolete local staging", async () => {
  const f = await fixture(), c = f.client(), selected = await c.createHousehold(await f.setup(), "Our list");
  await c.synchronize(selected.household, selected.list);
  const { change } = await import("../src/owner.mjs"); f.remote.fail = "beforeCommit";
  await assert.rejects(change(c, selected.household, "rotate", body(c.read(selected.household).proofs.at(-1)).members));
  const pending = c.read(selected.household).pendingMembership, m = body({ manifestBytes: pending.manifest.manifestBytes, signature: pending.manifest.signature });
  const other = { manifestBytes: b64(jsonBytes(Object.fromEntries(Object.entries(m).reverse()))) };
  other.signature = await sign(f.draft.device, manifestBytes(other));
  f.remote.proofs.push(other); f.remote.lastSigned = pending.manifest;
  f.remote.ownerFingerprint = await fingerprint(f.server.serverId, selected.household, f.draft.device.publicKeys);
  assert.equal(await c.discardInvalidMembership(selected.household), true);
  assert.equal(await manifestHash(c.read(selected.household).proofs.at(-1)), await manifestHash(other));
  assert.equal(c.read(selected.household).pendingMembership, null);
  assert.equal(c.read(selected.household).keys[`${selected.list}:3`], pending.keys[selected.list]); c.stop();
});
test("discard survives reload after competing proof is saved but epoch-key download fails", async () => {
  const f = await fixture(); let c = f.client();
  const selected = await c.createHousehold(await f.setup(), "Our list");
  await c.synchronize(selected.household, selected.list);
  const { change } = await import("../src/owner.mjs"); f.remote.fail = "beforeCommit";
  await assert.rejects(change(c, selected.household, "rotate", body(c.read(selected.household).proofs.at(-1)).members));
  const pending = c.read(selected.household).pendingMembership,
    m = body({ manifestBytes: pending.manifest.manifestBytes, signature: pending.manifest.signature });
  const other = { manifestBytes: b64(jsonBytes(Object.fromEntries(Object.entries(m).reverse()))) };
  other.signature = await sign(f.draft.device, manifestBytes(other));
  f.remote.proofs.push(other); f.remote.lastSigned = pending.manifest;
  f.remote.ownerFingerprint = await fingerprint(f.server.serverId, selected.household, f.draft.device.publicKeys);
  f.remote.fail = "packages";
  await assert.rejects(c.discardInvalidMembership(selected.household), e => e.code === "unavailable");
  assert.equal(body(c.read(selected.household).proofs.at(-1)).epoch, 3);
  assert.ok(c.read(selected.household).pendingMembership);
  c.stop(); c = f.client();
  assert.equal(await c.discardInvalidMembership(selected.household), true);
  await c.synchronize(selected.household, selected.list);
  assert.equal((await c.view(selected.household, selected.list)).name, "Our list");
  assert.equal(c.read(selected.household).pendingMembership, null); c.stop();
});
