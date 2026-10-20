import {
  requireThat,
  strictJson,
  keys,
  uuid,
  unb64,
  zero,
  equal,
  manifestBytes,
  manifestHash,
  bytes,
  digest,
} from "./wire.mjs";
import { verify } from "./crypto.mjs";
const manifestFields = [
  "version",
  "serverId",
  "householdId",
  "genesisHash",
  "epoch",
  "previousHash",
  "action",
  "signer",
  "members",
  "recovery",
  "lists",
  "packageHashes",
  "approvedJoins",
  "createdListId",
  "transferToDeviceId",
];
export function body(proof) {
  keys(
    proof,
    ["manifestBytes", "signature", "transferAcceptance"],
    ["manifestBytes", "signature"],
  );
  unb64(proof.signature, 64);
  if (proof.transferAcceptance) keys(proof.transferAcceptance, ["signature"]);
  return keys(
    strictJson(unb64(proof.manifestBytes), 4 * 1024 * 1024),
    manifestFields,
    manifestFields.slice(0, 13),
  );
}
export function devices(m) {
  return m.members.flatMap((member) => member.devices);
}
export function owners(m) {
  return m.members
    .filter((member) => member.role === "owner")
    .flatMap((member) => member.devices);
}
function unique(xs) {
  return new Set(xs).size === xs.length;
}
export function shape(m, server) {
  requireThat(
    m.version === 1 &&
      m.serverId === server &&
      Number.isSafeInteger(m.epoch) &&
      m.epoch > 0,
  );
  uuid(m.householdId);
  uuid(m.signer);
  unb64(m.genesisHash, 32);
  unb64(m.previousHash, 32);
  requireThat(
    Array.isArray(m.members) &&
      m.members.length >= 1 &&
      m.members.length <= 20 &&
      unique(m.members.map((x) => x.id)) &&
      m.members.some((x) => x.role === "owner"),
  );
  for (const member of m.members) {
    keys(member, ["id", "role", "devices"]);
    uuid(member.id);
    requireThat(
      ["owner", "editor"].includes(member.role) &&
        Array.isArray(member.devices) &&
        member.devices.length >= 1 &&
        member.devices.length <= 8,
    );
  }
  const all = [...devices(m), m.recovery];
  requireThat(
    unique(all.map((x) => x.id)) &&
      unique(all.map((x) => x.signingPublicKey)) &&
      unique(all.map((x) => x.recipientPublicKey)),
  );
  for (const d of all) {
    keys(d, ["id", "signingPublicKey", "recipientPublicKey"]);
    uuid(d.id);
    unb64(d.signingPublicKey, 32);
    unb64(d.recipientPublicKey, 32);
  }
  requireThat(
    Array.isArray(m.lists) &&
      m.lists.length <= 20 &&
      unique(m.lists.map((x) => x.listId)),
  );
  for (const l of m.lists) {
    keys(l, ["listId", "seq", "head", "snapshotId", "snapshotHash"]);
    uuid(l.listId);
    uuid(l.snapshotId);
    unb64(l.head, 32);
    unb64(l.snapshotHash, 32);
    requireThat(
      Number.isSafeInteger(l.seq) &&
        l.seq >= 0 &&
        (l.seq !== 0 || l.head === zero),
    );
  }
  requireThat(Array.isArray(m.packageHashes) && unique(m.packageHashes));
  m.packageHashes.forEach((x) => unb64(x, 32));
  keys(m.approvedJoins, Object.keys(m.approvedJoins));
  for (const [id, h] of Object.entries(m.approvedJoins)) {
    uuid(id);
    unb64(h, 32);
  }
}
export async function genesis(proof, server, household, pin) {
  const m = body(proof);
  shape(m, server.serverId);
  requireThat(
    (await manifestHash(proof)) === pin &&
      m.householdId === household &&
      m.epoch === 1 &&
      m.genesisHash === zero &&
      m.previousHash === zero &&
      m.action === "genesis",
  );
  requireThat(
    m.members.length === 1 &&
      m.members[0].role === "owner" &&
      m.members[0].devices.length === 1 &&
      m.lists.length === 0 &&
      m.packageHashes.length === 0 &&
      Object.keys(m.approvedJoins).length === 0 &&
      !m.createdListId &&
      !m.transferToDeviceId &&
      !proof.transferAcceptance,
  );
  const owner = m.members[0].devices[0];
  requireThat(m.signer === owner.id);
  await verify(owner.signingPublicKey, proof.signature, manifestBytes(proof));
  return m;
}
function sameSet(a, b) {
  return a.length === b.length && a.every((x) => b.includes(x));
}
export async function transition(
  proof,
  old,
  previousHash,
  genesisHash,
  server,
) {
  const m = body(proof);
  shape(m, server);
  requireThat(
    m.householdId === old.householdId &&
      m.genesisHash === genesisHash &&
      m.epoch === old.epoch + 1 &&
      m.previousHash === previousHash,
    "membership_conflict",
  );
  const recovery = m.signer === old.recovery.id;
  const signer = recovery
    ? old.recovery
    : owners(old).find((d) => d.id === m.signer);
  requireThat(signer, "forbidden");
  await verify(signer.signingPublicKey, proof.signature, manifestBytes(proof));
  const before = Object.fromEntries(devices(old).map((d) => [d.id, d])),
    after = Object.fromEntries(devices(m).map((d) => [d.id, d])),
    oldMember = Object.fromEntries(
      old.members.flatMap((x) => x.devices.map((d) => [d.id, x.id])),
    ),
    newMember = Object.fromEntries(
      m.members.flatMap((x) => x.devices.map((d) => [d.id, x.id])),
    );
  for (const id of Object.keys(before).filter((id) => after[id]))
    requireThat(
      equal(before[id], after[id]) && oldMember[id] === newMember[id],
    );
  const preceding = [...devices(old), old.recovery],
    fresh = (d) =>
      preceding.every(
        (x) =>
          x.id !== d.id &&
          x.signingPublicKey !== d.signingPublicKey &&
          x.recipientPublicKey !== d.recipientPublicKey,
      );
  if (m.action === "replace_recovery") requireThat(fresh(m.recovery));
  if (recovery) {
    requireThat(
      devices(m).every(fresh) &&
        m.action === "recover" &&
        equal(m.recovery, old.recovery) &&
        m.members.length === 1 &&
        m.members[0].role === "owner" &&
        m.members[0].devices.length === 1 &&
        Object.keys(before).every((id) => !after[id]) &&
        !old.members.some((x) => x.id === m.members[0].id) &&
        Object.keys(m.approvedJoins).length === 0 &&
        !m.createdListId &&
        !m.transferToDeviceId &&
        !proof.transferAcceptance,
      "forbidden",
    );
  } else {
    switch (m.action) {
      case "rotate":
      case "create_list":
        requireThat(
          equal(m.members, old.members) &&
            equal(m.recovery, old.recovery) &&
            Object.keys(m.approvedJoins).length === 0,
        );
        break;
      case "replace_recovery":
        requireThat(
          equal(m.members, old.members) &&
            !equal(m.recovery, old.recovery) &&
            m.recovery.id !== old.recovery.id &&
            Object.keys(m.approvedJoins).length === 0,
        );
        break;
      case "remove":
        requireThat(
          equal(m.recovery, old.recovery) &&
            Object.keys(m.approvedJoins).length === 0 &&
            Object.keys(after).every((id) => before[id]) &&
            m.members.every((x) =>
              old.members.some((y) => x.id === y.id && x.role === y.role),
            ),
        );
        break;
      case "approve_join":
        requireThat(
          equal(m.recovery, old.recovery) &&
            Object.keys(m.approvedJoins).length === 1 &&
            Object.keys(after).filter((id) => !before[id]).length === 1 &&
            Object.keys(before).every((id) => after[id]) &&
            old.members.every((x) =>
              m.members.some((y) => x.id === y.id && x.role === y.role),
            ),
        );
        break;
      case "transfer": {
        requireThat(
          equal(m.recovery, old.recovery) &&
            Object.keys(m.approvedJoins).length === 0 &&
            equal(after, before) &&
            sameSet(
              m.members.map((x) => x.id),
              old.members.map((x) => x.id),
            ),
        );
        const target = old.members.find((x) =>
          x.devices.some((d) => d.id === m.transferToDeviceId),
        );
        requireThat(
          target &&
            target.id !== oldMember[m.signer] &&
            m.members.find((x) => x.id === target.id).role === "owner" &&
            m.members.every(
              (x) =>
                x.role === old.members.find((y) => x.id === y.id).role ||
                [target.id, oldMember[m.signer]].includes(x.id),
            ) &&
            proof.transferAcceptance,
        );
        await verify(
          before[m.transferToDeviceId].signingPublicKey,
          proof.transferAcceptance.signature,
          bytes(
            "USUALS/TRANSFER/1\0",
            await digest(unb64(proof.manifestBytes)),
          ),
        );
        break;
      }
      default:
        requireThat(false, "forbidden");
    }
  }
  if (m.action !== "transfer")
    requireThat(!m.transferToDeviceId && !proof.transferAcceptance);
  if (m.action !== "create_list") requireThat(!m.createdListId);
  const expected = old.lists.map((x) => x.listId);
  if (m.action === "create_list") {
    uuid(m.createdListId);
    requireThat(!expected.includes(m.createdListId));
    expected.push(m.createdListId);
  }
  requireThat(
    sameSet(
      expected,
      m.lists.map((x) => x.listId),
    ),
  );
  for (const prior of old.lists) {
    const next = m.lists.find((x) => x.listId === prior.listId);
    requireThat(
      next.seq >= prior.seq &&
        (next.seq !== prior.seq || next.head === prior.head),
      "membership_conflict",
    );
  }
  requireThat(
    m.packageHashes.length === m.lists.length * (devices(m).length + 1),
  );
  return m;
}
export async function verifyChain(proofs, server, household, pin) {
  requireThat(proofs.length > 0 && proofs.length <= 10000);
  let m = await genesis(proofs[0], server, household, pin),
    h = pin;
  const retired = { id: new Set(), signing: new Set(), recipient: new Set() };
  for (let i = 1; i < proofs.length; i++) {
    const next = await transition(proofs[i], m, h, pin, server.serverId);
    const old = [...devices(m), m.recovery],
      added = [...devices(next), next.recovery].filter(
        (d) => !old.some((o) => equal(o, d)),
      );
    for (const d of added)
      requireThat(
        !retired.id.has(d.id) &&
          !retired.signing.has(d.signingPublicKey) &&
          !retired.recipient.has(d.recipientPublicKey),
      );
    for (const d of old) {
      retired.id.add(d.id);
      retired.signing.add(d.signingPublicKey);
      retired.recipient.add(d.recipientPublicKey);
    }
    m = next;
    h = await manifestHash(proofs[i]);
  }
  return { manifest: m, hash: h };
}
