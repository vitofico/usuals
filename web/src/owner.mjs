import { b64, unb64, jsonBytes, zero, equal, requireThat, manifestBytes,
  manifestHash, packageHash, envelopeHash, parseHeader, fingerprint, hash, joinBytes, parseJoin, uuid } from "./wire.mjs";
import { random, sign, verify, seal, hpkeSeal } from "./crypto.mjs";
import { body, owners, devices, genesis, transition, verifyChain } from "./membership.mjs";
import { emptyProjection, snapshotDecode } from "./reducer.mjs";
import { packageContext, packageBytes } from "./wire.mjs";
import { admitKey, macKey, verifyJoinMac, joinCode, descriptorAnchor, inviteLink, MAX_INVITE_LIFETIME } from "./pairing.mjs";

function owner(h) {
  const m = body(h.proofs.at(-1));
  requireThat(!h.removed && owners(m).some(d => equal(d, h.device.publicKeys)), "forbidden");
  return m;
}
async function wrap(h, list, epoch, predecessorHash, recipient, key) {
  const p = { listId: list, epoch, predecessorHash,
    recipientFingerprint: await fingerprint(h.server.serverId, h.id, recipient) };
  p.ciphertext = b64(await hpkeSeal(unb64(recipient.recipientPublicKey, 32), key, packageContext(h.server.serverId, h.id, p)));
  p.signature = await sign(h.device, packageBytes(h.server.serverId, h.id, p));
  return p;
}
export async function beginCreation(c, setup, name) {
  c.scope.check();
  requireThat(setup.origin === c.origin && typeof name === "string" && name.trim() && name.length <= 100);
  const m = await c.scope.wait(genesis(setup.proof, setup.server, body(setup.proof).householdId, await manifestHash(setup.proof)));
  requireThat(equal(m.members[0].devices[0], setup.device.publicKeys));
  await c.scope.wait(c.vault.mutate(s => {
    c.scope.check();
    requireThat(!s.joining && !s.creating && !s.households[m.householdId], "conflict");
    s.creating = { ...setup, listId: crypto.randomUUID(), name: name.trim() };
  }));
  return finishCreation(c);
}
export async function finishCreation(c) {
  let p = c.vault.read().creating;
  requireThat(p, "not_found");
  const id = body(p.proof).householdId;
  if (!c.vault.read().households[id]) {
    requireThat(equal(await c.info(), p.server), "unverified");
    await c.scope.wait(c.vault.mutate(s => { c.scope.check();
      requireThat(equal(s.creating, p)); s.creating.rejected = false;
    }));
    p = c.vault.read().creating;
    const bootstrapProof = await c.proof(p.device, p.server, "bootstrap", false);
    let proof;
    try { proof = await c.call("/api/households", { method: "POST", status: 201,
      body: { manifest: { ...p.proof, packages: [], snapshotRefs: [] }, bootstrapProof } }); }
    catch (error) {
      // This endpoint checks exact existing genesis before its permanent quota.
      // Network failures retain the setup, because creation may already have committed.
      if (error.code === "quota_exceeded") await c.scope.wait(c.vault.mutate(s => { c.scope.check();
        requireThat(equal(s.creating, p)); s.creating.rejected = true;
      }));
      throw error;
    }
    requireThat(equal(proof, p.proof), "unverified");
    await c.scope.wait(c.vault.mutate(s => {
    c.scope.check();
      requireThat(equal(s.creating, p) && !s.households[id]);
      s.households[id] = { id, origin: c.origin, server: p.server, device: p.device, proofs: [p.proof],
        keys: {}, headPins: {}, receiptPins: {}, lists: {}, session: null, removed: false, linked: true };
    }));
  }
  let h = c.read(id);
  if (h.pendingMembership) await resumeMembership(c, id);
  h = c.read(id);
  if (!body(h.proofs.at(-1)).lists.some(l => l.listId === p.listId))
    await change(c, id, "create_list", owner(h).members, {}, { id: p.listId, name: p.name });
  await c.scope.wait(c.vault.mutate(s => {
    c.scope.check(); s.creating = null; }));
  return { household: id, list: p.listId };
}
export async function abandonRejectedCreation(c) {
  await c.scope.wait(c.vault.mutate(s => { c.scope.check();
    requireThat(s.creating?.rejected && !s.households[body(s.creating.proof).householdId], "membership_conflict");
    s.creating = null;
  }));
}
export async function change(c, id, action, members, joins = {}, created = null, approval = null) {
  const h = c.read(id), before = owner(h);
  requireThat(!h.pendingMembership, "membership_conflict");
  requireThat(Object.values(h.lists).every(l => !l.pending.length), "pending_changes");
  const lists = [...before.lists, ...(created ? [{ listId: created.id, seq: 0, head: zero,
    snapshotId: crypto.randomUUID(), snapshotHash: zero }] : [])];
  const draft = { ...before, epoch: before.epoch + 1, genesisHash: await manifestHash(h.proofs[0]),
    previousHash: await manifestHash(h.proofs.at(-1)), action, signer: h.device.publicKeys.id,
    members, lists, packageHashes: [], approvedJoins: joins };
  delete draft.createdListId; delete draft.transferToDeviceId;
  if (created) draft.createdListId = created.id;
  await c.save(id, s => { s.pendingMembership = { draft, keys: Object.fromEntries(lists.map(l => [l.listId, b64(random(32))])),
    snapshots: {}, manifest: null, name: created?.name ?? null, approval }; });
  await resumeMembership(c, id);
}
export async function resumeMembership(c, id, retryExpired = true) {
  let h = c.read(id), p = h.pendingMembership;
  if (!p) return;
  owner(h);
  const before = body(h.proofs.at(-1)), previous = await c.scope.wait(manifestHash(h.proofs.at(-1))),
    pin = await c.scope.wait(manifestHash(h.proofs[0]));
  requireThat(p.draft.epoch === before.epoch + 1 && p.draft.previousHash === previous, "membership_conflict");
  const existing = await c.authorized(id, `/v1/households/${id}/membership?afterEpoch=${before.epoch}`, { max: 8 * 1024 * 1024 });
  if (existing.manifests.length) {
    requireThat(existing.manifests.length === 1 && existing.nextEpoch === p.draft.epoch && p.manifest &&
      await manifestHash(existing.manifests[0]) === await manifestHash(p.manifest), "membership_conflict");
  } else {
    requireThat(existing.nextEpoch === before.epoch);
    if (!p.manifest) {
      const refs = [];
      for (const list of p.draft.lists) {
        let snapshot = p.snapshots[list.listId];
        if (!snapshot) {
          const checkpoint = list.listId === p.draft.createdListId
            ? { seq: 0, head: zero, projection: { ...emptyProjection(), name: p.name } }
            : await c.checkpoint(id, list.listId);
          // Validate the native snapshot shape before encryption. No plaintext is sent to the relay.
          await c.scope.wait(snapshotDecode({ schema: 1, normalizer: 1, projection: checkpoint.projection }));
          const key = unb64(p.keys[list.listId], 32), keyHash = await hash(key);
          const envelope = await c.scope.wait(seal({ serverId: h.server.serverId, householdId: id,
            listId: list.listId, authorDeviceId: h.device.publicKeys.id, intentId: crypto.randomUUID(), envelopeId: crypto.randomUUID(),
            epoch: p.draft.epoch, manifestHash: previous, baseSeq: checkpoint.seq, baseHead: checkpoint.head },
          jsonBytes({ schema: 1, normalizer: 1, projection: checkpoint.projection }), key, h.device,
          () => c.vault.reserve(`${id}:${list.listId}:${p.draft.epoch}:${h.device.publicKeys.id}:${keyHash}`), 7 * 1024 * 1024));
          snapshot = { candidate: crypto.randomUUID(), envelope };
          await c.save(id, s => { s.pendingMembership.snapshots[list.listId] = snapshot; });
          p = c.read(id).pendingMembership;
        }
        const header = parseHeader(snapshot.envelope.header);
        const ref = await c.authorized(id, `/v1/households/${id}/snapshot-candidates/${snapshot.candidate}`, {
          method: "PUT", body: { envelope: snapshot.envelope, seq: header.baseSeq, head: header.baseHead } });
        requireThat(ref.candidateId === snapshot.candidate && ref.listId === list.listId && ref.seq === header.baseSeq &&
          ref.head === header.baseHead && ref.sha256 === await envelopeHash(snapshot.envelope), "unverified");
        refs.push(ref);
      }
      const packages = [];
      for (const list of p.draft.lists) for (const recipient of [...devices(p.draft), p.draft.recovery])
        packages.push(await c.scope.wait(wrap(h, list.listId, p.draft.epoch, previous, recipient, unb64(p.keys[list.listId], 32))));
      const m = { ...p.draft, lists: refs.map(r => ({ listId: r.listId, seq: r.seq, head: r.head, snapshotId: r.candidateId, snapshotHash: r.sha256 })),
        packageHashes: await c.scope.wait(Promise.all(packages.map(p => packageHash(h.server.serverId, id, p)))) };
      const proof = { manifestBytes: b64(jsonBytes(m)) };
      proof.signature = await c.scope.wait(sign(h.device, manifestBytes(proof)));
      await c.scope.wait(transition(proof, before, previous, pin, h.server.serverId));
      await c.save(id, s => { s.pendingMembership.manifest = { ...proof, packages, snapshotRefs: refs }; });
      p = c.read(id).pendingMembership;
    }
    try {
      const response = await c.authorized(id, `/v1/households/${id}/${p.draft.action === "create_list" ? "lists" : "membership"}`, {
        method: "POST", body: p.manifest, status: p.draft.action === "create_list" ? 201 : 200, max: 8 * 1024 * 1024 });
      requireThat(response.manifests.length === 1 && response.nextEpoch === p.draft.epoch &&
        await manifestHash(response.manifests[0]) === await manifestHash(p.manifest), "unverified");
    } catch (e) {
      if (e.code !== "candidate_expired" || !retryExpired) throw e;
      await c.save(id, s => { s.pendingMembership.manifest = null;
        for (const snapshot of Object.values(s.pendingMembership.snapshots)) snapshot.candidate = crypto.randomUUID(); });
      return resumeMembership(c, id, false);
    }
  }
  const proof = { manifestBytes: p.manifest.manifestBytes, signature: p.manifest.signature };
  await c.scope.wait(transition(proof, before, previous, pin, h.server.serverId));
  await c.save(id, s => {
    s.proofs.push(proof);
    for (const [list, key] of Object.entries(p.keys)) s.keys[`${list}:${p.draft.epoch}`] = key;
    s.pendingMembership = null;
  });
}
/** Creates a one-use link; only this device keeps its key, so only it can verify who used the link. */
export async function invite(c, id) {
  const h = await c.refresh(id); owner(h);
  const key = random(32), memberId = crypto.randomUUID();
  const descriptor = jsonBytes({ version: 2, origin: c.origin, serverId: h.server.serverId, householdId: id,
    genesisHash: await manifestHash(h.proofs[0]), ownerDeviceId: h.device.publicKeys.id,
    ownerFingerprint: await fingerprint(h.server.serverId, id, h.device.publicKeys), memberId, role: "editor" });
  const value = await c.authorized(id, `/v1/households/${id}/invites`, { method: "POST",
    body: { memberId, role: "editor", secretHash: await hash(await admitKey(key)), descriptor: b64(descriptor) } });
  uuid(value.inviteId);
  requireThat(value.secret == null && Number.isSafeInteger(value.expiresAt) && value.expiresAt > c.clock() && value.expiresAt <= c.clock() + MAX_INVITE_LIFETIME);
  await c.save(id, s => {
    s.invites = Object.fromEntries(Object.entries(s.invites ?? {}).filter(([, i]) => i.expiresAt > c.clock()));
    s.invites[value.inviteId] = { key: b64(key), memberId, expiresAt: value.expiresAt };
  });
  return inviteLink(c.origin, value.inviteId, key, await descriptorAnchor(descriptor));
}
export async function discardInvalidPending(c, id) {
  return discardPending(c, id);
}
async function discardPending(c, id, expired = null) {
  const h = c.read(id), p = h.pendingMembership;
  if (!p) return false;
  const previous = await manifestHash(h.proofs.at(-1)), before = body(h.proofs.at(-1));
  if (previous !== p.draft.previousHash) {
    // A prior refresh may have saved the competing epoch before its key download failed.
    await c.scope.wait(verifyChain(h.proofs, h.server, id, await manifestHash(h.proofs[0])));
    const base = h.proofs.find(proof => body(proof).epoch === p.draft.epoch - 1),
      next = h.proofs.find(proof => body(proof).epoch === p.draft.epoch);
    requireThat(base && next && await manifestHash(base) === p.draft.previousHash, "membership_conflict");
    const committed = p.manifest && await manifestHash(next) === await manifestHash(p.manifest);
    await c.save(id, s => {
      requireThat(equal(s.pendingMembership, p));
      if (committed) for (const [list, key] of Object.entries(p.keys)) s.keys[`${list}:${p.draft.epoch}`] = key;
      s.pendingMembership = null;
    });
    return !committed;
  }
  const page = await c.authorized(id, `/v1/households/${id}/membership?afterEpoch=${before.epoch}`, { max: 8 * 1024 * 1024 });
  let invalid = !p.manifest || equal(expired, p);
  if (page.manifests.length) {
    requireThat(page.manifests.length === 1);
    if (p.manifest && await manifestHash(page.manifests[0]) === await manifestHash(p.manifest)) {
      await resumeMembership(c, id); return false;
    }
    // Refresh verifies the competing chain, cutoff bridges and retained receipt witnesses.
    try { await c.refresh(id, false); }
    catch (e) { if (e.code !== "forbidden" || !c.read(id).removed) throw e; }
    requireThat(await manifestHash(c.read(id).proofs.at(-1)) !== previous, "membership_conflict");
    invalid = true;
  } else if (p.manifest) {
    requireThat(page.nextEpoch === before.epoch);
    for (const cutoff of body({ manifestBytes: p.manifest.manifestBytes, signature: p.manifest.signature }).lists) {
      if (!before.lists.some(l => l.listId === cutoff.listId)) continue;
      const checkpoint = await c.checkpoint(id, cutoff.listId);
      requireThat(checkpoint.seq >= cutoff.seq && (checkpoint.seq !== cutoff.seq || checkpoint.head === cutoff.head), "history_unverifiable");
      if (checkpoint.seq > cutoff.seq) invalid = true;
    }
    if (!invalid && p.draft.action === "approve_join" && p.approval) {
      const verified = await validateApplicant(c, id, p.approval, false), a = verified.assertion;
      requireThat(p.draft.approvedJoins[a.requestId] === await hash(joinBytes(a)), "unverified");
      const result = await c.authorized(id, `/v1/households/${id}/join-requests/${a.requestId}/expired`, {
        method: "POST", body: p.approval });
      requireThat(result.requestId === a.requestId && result.expiresAt === a.expiresAt, "unverified");
      // Resolve a commit that won the household lock before the expiry check.
      // Ambiguous failures leave all pending keys in the vault for another retry.
      return discardPending(c, id, p);
    }
  }
  requireThat(invalid, "membership_conflict");
  await c.save(id, s => { requireThat(equal(s.pendingMembership, p)); s.pendingMembership = null; });
  return true;
}
export async function validateApplicant(c, id, pending, checkExpiry = true) {
  const h = c.read(id); owner(h);
  const a = parseJoin(unb64(pending.assertion));
  requireThat(equal([...joinBytes(a)], [...unb64(pending.assertion)]) && a.requestId === pending.requestId &&
    a.origin === c.origin && a.serverId === h.server.serverId && a.householdId === id &&
    a.genesisHash === await manifestHash(h.proofs[0]), "unverified");
  if (checkExpiry) requireThat(a.expiresAt > c.clock(), "invite_expired");
  await c.scope.wait(verify(a.device.signingPublicKey, pending.signature, joinBytes(a)));
  return { assertion: a, pending };
}
/**
 * The relay cannot forge this: the MAC needs the key that only this device's link carried. Every invite names a fresh
 * member, so it is spent once that member is in the roster, even when the approval's response was lost.
 */
async function verifiedJoin(c, id, pending) {
  const { assertion: a } = await validateApplicant(c, id, pending, false), h = c.read(id), invite = h.invites?.[a.inviteId];
  requireThat(invite && a.memberId === invite.memberId && a.expiresAt === invite.expiresAt &&
    !owner(h).members.some(member => member.id === a.memberId), "unverified");
  const key = unb64(invite.key, 32), mac = await macKey(key);
  requireThat(a.secretHash === await hash(await admitKey(key)) && await verifyJoinMac(mac, joinBytes(a), pending.mac), "unverified");
  return { assertion: a, code: await joinCode(mac, joinBytes(a)) };
}
export async function applicants(c, id) {
  owner(await c.refresh(id));
  const result = await c.authorized(id, `/v1/households/${id}/join-requests`);
  requireThat(Array.isArray(result.requests) && result.requests.length <= 10);
  const shown = result.requests.filter(p => { try { uuid(p.requestId); return true; } catch { return false; } });
  return c.scope.wait(Promise.all(shown.map(async pending => {
    try {
      requireThat(pending.assertion && parseJoin(unb64(pending.assertion)).expiresAt > c.clock(), "invite_expired");
      return { requestId: pending.requestId, code: (await verifiedJoin(c, id, pending)).code, verified: true, pending };
    } catch { return { requestId: pending.requestId, code: null, verified: false, pending }; }
  })));
}
export async function approve(c, id, pending) {
  const h = await c.refresh(id), m = owner(h), { assertion: a } = await validateApplicant(c, id, pending, false);
  const assertionHash = await hash(joinBytes(a));
  const approved = h.proofs.some(proof => body(proof).approvedJoins[a.requestId] === assertionHash);
  const forget = () => c.save(id, s => { if (s.invites) delete s.invites[a.inviteId]; });
  if (approved && m.members.some(member => member.id === a.memberId && member.devices.some(d => equal(d, a.device)))) return forget();
  requireThat(!approved && a.expiresAt > c.clock(), "invite_expired");
  await verifiedJoin(c, id, pending);
  requireThat(!devices(m).some(d => d.id === a.device.id), "conflict");
  await change(c, id, "approve_join", [...m.members, { id: a.memberId, role: "editor", devices: [a.device] }], { [a.requestId]: assertionHash }, null, pending);
  return forget();
}
export async function remove(c, id, device) {
  const h = await c.refresh(id), m = owner(h);
  uuid(device);
  requireThat(device !== h.device.publicKeys.id, "forbidden");
  if (!devices(m).some(d => d.id === device)) return;
  return change(c, id, "remove", m.members.map(x => ({ ...x, devices: x.devices.filter(d => d.id !== device) })).filter(x => x.devices.length));
}
