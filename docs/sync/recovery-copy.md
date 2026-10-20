# Frozen server recovery copies, format 1

This format restores shopping facts to an independent local list. It grants no
server authority. Restored household ids remain permanently frozen, including
for formerly valid devices and recovery keys. No public recovery-export route
exists. `recovery-export` is an offline operator command with stopped traffic.

On the phone, enter the separate recovery key and choose **Create a server
backup request**, then open the matching encrypted kit. The saved JSON contains
only public keys, origin, server and household ids, and a full fingerprint.
It works with an unreadable old private vault and does not contact the relay.
Keep the kit and separate secret on the phone. Never send them to the operator.

The operator identifies the known owner through an independent trusted channel,
compares all 64 hexadecimal fingerprint characters, selects one list from that
household, and privately writes a grant:

```json
{"version":1,"origin":"https://usuals.example","serverId":"PUBLIC_SERVER_ID","household":"HOUSEHOLD_UUID","list":"LIST_UUID","recipient":{"id":"DEVICE_UUID","signingPublicKey":"PUBLIC_KEY","recipientPublicKey":"PUBLIC_KEY"},"verifiedFingerprint":"INDEPENDENTLY_VERIFIED_64_HEX"}
```

Copy the request's public fields, omit its `fingerprint`, and add `list` plus the
independently checked `verifiedFingerprint`. A request received over an untrusted
channel is not proof of ownership. The grant must be an operator-owned regular
file in an operator-owned directory, neither writable by others, normally modes
0600/0700. Relative no-follow access uses a protected directory handle. The last
signed restored roster must identify that exact recipient as an owner or its
active recovery principal. A retired replacement kit cannot decrypt a backup
from after its replacement, and a new kit cannot decrypt older packages. Retain
retired encrypted kits and separate secrets until their backups expire, or use
a surviving owner device that still holds the matching private identity.

The command takes a repeatable-read snapshot and writes ciphertext only:

1. Magic ASCII bytes `USUALS-FROZEN`, NUL, byte 1.
2. Big-endian unsigned 32-bit header length and that many UTF-8 JSON bytes.
3. Each ordered inventory record: unsigned 32-bit length then exact bytes.
4. Physical EOF. No compression, directory extraction or optional suffix.

The header is `SignedFrozenInventory(inventory, signature)`. `inventory` is
base64url exact JSON `FrozenInventory`. The relay's pinned Ed25519 receipt key
signs `Wire.domain("FROZEN-EXPORT", exactInventoryBytes)`. Inventory fields bind
version, origin, server, household/genesis, one list, recipient/public fingerprint,
restore fence, epoch/manifest hash, snapshot digest/cutoff and terminal sequence/
head, plus every ordered record's kind, length and SHA256 hash. This attests the
selected database snapshot's completeness. It does not attest present freshness.

Records contain the full contiguous public signed manifest chain, the selected
recipient's current package, one encrypted snapshot and contiguous encrypted
operation pages through the captured terminal head. Limits: 128 MiB total,
4096 records, 1 MiB header, 8 MiB per manifest, 16 KiB package, 10 MiB snapshot
or operation page and 100 operations per page. Records are read separately;
only the verified bounded shopping projection is retained for the review/save.
Exports stage mode-0600 bytes, fsync, atomically publish by same-filesystem hard
link without replacing any final name, then fsync the parent. A final file is
complete even if cleanup is interrupted. A partial staging directory has no
published final name and may be removed after the process is known stopped.

The phone verifies framing, exact hashes/signatures, pinned identity/genesis,
full membership chain, exact overlapping kit/cached membership pins, authorized
package signer/recipient/context and snapshot signature/AEAD. It verifies every
operation's contiguous receipt chain, author signature, AEAD and reducer version,
then terminal head and EOF before offering **Save as a local copy**. A proven
same-epoch manifest fork or same-sequence head fork fails closed. Historical
manifest cutoffs are comparisons, not proof of ancestry to a later snapshot.
Missing pre-snapshot evidence or unreadable cached pins produces an ancestry
warning; lower known epoch/head produces an older-backup warning. Fresh kit-only
recovery cannot prove that this is the latest server state.

The preview counts live items and past purchases. Saving uses that exact
immutable verified payload without reopening the file. Scope creation, chosen
copy name and shopping facts commit in one Room transaction. New local genesis
has no old authority, cursor, command lineage or pending envelopes. Existing
lists, unsent edits, sessions, membership/head pins and inaccessible vault bytes
are preserved. Compare retained work explicitly, then share through a fresh
household/list and fresh keys if desired. Duplicate saves are separate copies,
not a deduplicating merge. Normal membership recovery remains a distinct flow.
