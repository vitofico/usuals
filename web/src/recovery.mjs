import { b64, unb64, jsonBytes, strictJson, zero, manifestBytes, manifestHash, equal, hash, requireThat } from "./wire.mjs";
import { newDevice, random, sign, aesEncrypt, aesDecrypt } from "./crypto.mjs";
import { body, genesis } from "./membership.mjs";

export async function prepareHousehold(origin, server) {
  requireThat((await hash(unb64(server.receiptPublicKey, 32))) === server.serverId && server.protocolVersions.includes(1));
  const device = await newDevice(), recovery = await newDevice();
  const m = { version: 1, serverId: server.serverId, householdId: crypto.randomUUID(),
    genesisHash: zero, epoch: 1, previousHash: zero, action: "genesis", signer: device.publicKeys.id,
    members: [{ id: crypto.randomUUID(), role: "owner", devices: [device.publicKeys] }],
    recovery: recovery.publicKeys, lists: [], packageHashes: [], approvedJoins: {} };
  const proof = { manifestBytes: b64(jsonBytes(m)) };
  proof.signature = await sign(device, manifestBytes(proof));
  await genesis(proof, server, m.householdId, await manifestHash(proof));
  const kit = { version: 1, origin, server, household: m.householdId, genesis: proof, recovery, anchor: [] };
  const secret = b64(random(32));
  const file = jsonBytes({ version: 1, ciphertext: b64(await aesEncrypt(unb64(secret, 32), jsonBytes(kit), new TextEncoder().encode("USUALS/RECOVERY-KIT/1\0"))) });
  return { origin, server, device, proof, file, secret };
}

export async function verifySavedHousehold(draft, file, secret) {
  requireThat(file instanceof Uint8Array && file.length === draft.file.length && file.every((v, i) => v === draft.file[i]) && secret === draft.secret, "unverified");
  const encoded = strictJson(file, 8 * 1024 * 1024);
  requireThat(encoded.version === 1, "unverified");
  const kit = strictJson(await aesDecrypt(unb64(secret, 32), unb64(encoded.ciphertext), new TextEncoder().encode("USUALS/RECOVERY-KIT/1\0")), 6 * 1024 * 1024);
  const m = body(draft.proof);
  requireThat(kit.version === 1 && kit.origin === draft.origin && equal(kit.server, draft.server) &&
    kit.household === m.householdId && equal(kit.genesis, draft.proof) && equal(kit.recovery.publicKeys, m.recovery), "unverified");
  // The persisted setup retains only the owner's keys. Recovery secrets stay in the saved kit.
  return { origin: draft.origin, server: draft.server, device: draft.device, proof: draft.proof };
}
