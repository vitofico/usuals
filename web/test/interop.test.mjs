import test from "node:test";
import assert from "node:assert/strict";
import { readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
import {
  b64,
  unb64,
  jsonBytes,
  packageBytes,
  packageContext,
  fingerprint,
  packageHash,
  parseHeader,
  manifestHash,
  joinBytes,
  hash,
  strictJson,
  zero,
  envelopeHash,
} from "../src/wire.mjs";
import {
  open,
  unwrap,
  seal,
  hpkeSeal,
  sign,
  verifyReceipt,
} from "../src/crypto.mjs";
import { verifyChain, body } from "../src/membership.mjs";
import { reduce, snapshotDecode } from "../src/reducer.mjs";
const directory = process.env.COMANDA_WEB_INTEROP_DIR;
test(
  "Kotlin Tink and browser WebCrypto interoperate in both directions",
  { skip: !directory },
  async () => {
    const f = JSON.parse(
        await readFile(join(directory, "kotlin-input.json"), "utf8"),
      ),
      proofs = [f.genesis, f.created, f.approved];
    const chain = await verifyChain(
      proofs,
      f.server,
      f.householdId,
      await manifestHash(proofs[0]),
    );
    assert.equal(chain.manifest.epoch, 3);
    assert.equal(
      await hash(joinBytes(f.joinAssertion)),
      chain.manifest.approvedJoins[f.joinAssertion.requestId],
    );
    assert.deepEqual(
      await unwrap(
        f.server.serverId,
        f.householdId,
        f.keyPackage,
        f.recipient,
        body(proofs[1]).members[0].devices[0].signingPublicKey,
      ),
      unb64(f.key, 32),
    );
    assert.deepEqual(
      await open(
        f.envelope,
        unb64(f.key, 32),
        f.recipient.publicKeys.signingPublicKey,
      ),
      unb64(f.plaintext),
    );
    await verifyReceipt(
      f.envelope,
      f.receipt,
      f.server.receiptPublicKey,
      f.receipt.seq,
      f.receipt.previousHead,
    );
    const snapshotAuthor = body(proofs[1]).members[0].devices[0];
    const snapshot = await snapshotDecode(
      strictJson(
        await open(
          f.snapshot.envelope,
          unb64(f.key, 32),
          snapshotAuthor.signingPublicKey,
          10 * 1024 * 1024,
        ),
        7 * 1024 * 1024,
        true,
      ),
    );
    assert.equal(
      (await reduce(snapshot, strictJson(unb64(f.plaintext), 65536, true)))
        .outcome,
      "APPLIED",
    );
    const previousHeader = parseHeader(f.envelope.header),
      envelope = await seal(
        {
          ...previousHeader,
          authorDeviceId: f.recipient.publicKeys.id,
          envelopeId: crypto.randomUUID(),
        },
        unb64(f.plaintext),
        unb64(f.key, 32),
        f.recipient,
        async () => {},
      );
    const p = {
      listId: f.listId,
      recipientFingerprint: await fingerprint(
        f.server.serverId,
        f.householdId,
        f.recipient.publicKeys,
      ),
      epoch: chain.manifest.epoch,
      predecessorHash: chain.manifest.previousHash,
      ciphertext: "",
      signature: "",
    };
    p.ciphertext = b64(
      await hpkeSeal(
        unb64(f.recipient.publicKeys.recipientPublicKey, 32),
        unb64(f.key, 32),
        packageContext(f.server.serverId, f.householdId, p),
      ),
    );
    p.signature = await sign(
      f.recipient,
      packageBytes(f.server.serverId, f.householdId, p),
    );
    await writeFile(
      join(directory, "browser-output.json"),
      JSON.stringify({ envelope, package: p }),
    );
    for (const mutate of [
      (proof) => ({
        ...proof,
        signature:
          proof.signature.slice(0, -1) +
          (proof.signature.endsWith("A") ? "B" : "A"),
      }),
      (proof) => ({
        ...proof,
        manifestBytes: b64(jsonBytes({ ...body(proof), action: "rotate" })),
      }),
    ]) {
      await assert.rejects(
        verifyChain(
          [proofs[0], proofs[1], mutate(proofs[2])],
          f.server,
          f.householdId,
          await manifestHash(proofs[0]),
        ),
      );
    }
    await assert.rejects(
      verifyReceipt(
        f.envelope,
        { ...f.receipt, head: zero },
        f.server.receiptPublicKey,
        f.receipt.seq,
        f.receipt.previousHead,
      ),
    );
  },
);
