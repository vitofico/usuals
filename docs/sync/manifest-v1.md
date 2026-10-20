# Serialized membership body v1

S2 makes the S0 signed body concrete. This is UTF-8 JSON with unique decoded
keys, no unknown fields or null values, maximum depth 32, 65,536 value nodes,
4,096 keys per object, 64 characters per key and at most 4 MiB decoded bytes. The
relay hashes/signs the original bytes; field order and whitespace are not
canonicalized. Binary values are unpadded canonical base64url. Optional fields
are omitted, rather than null. Routing IDs are canonical lowercase UUIDs.

`Manifest` in `sync/Wire.kt` is the executable schema. Required fields are:

- `version` (1), `serverId`, `householdId`, `genesisHash`, `epoch`, `previousHash`.
- `action`, one of `genesis`, `create_list`, `rotate`, `approve_join`, `remove`,
  `transfer`, `replace_recovery`, `recover`.
- `signer`, the signing device or recovery principal UUID.
- `members`: at most 20 `{id, role, devices}` records. Role is `owner` or
  `editor`; at least one owner remains. Each has one to eight devices.
- Device/recovery records are `{id, signingPublicKey, recipientPublicKey}`.
  Keys are raw 32-byte Ed25519/X25519 public keys. Device IDs and public keys
  are unique. A retained device cannot change keys or member ID.
- `recovery`: one distinct signing/recipient principal, never a normal member.
- `lists`: at most 20 `{listId, seq, head, snapshotId, snapshotHash}` cutoffs.
- `packageHashes`: the exact set of signed package hashes for all list/recipient
  pairs. `approvedJoins`: request UUID to SHA-256 of the complete signed join
  assertion input. Normal rotations use an empty map.
- Optional `createdListId` exists only for `create_list`.
- Optional `transferToDeviceId` exists only for `transfer`. Its acceptance
  signature is outside the body, as defined in security-v1.md.

Genesis is one owner with one device and a recovery principal. It has empty
lists, packages and join approvals, epoch 1 and zero genesis/predecessor fields.
It needs a consumed bootstrap token and purpose-bound proof for the exact
owner key/device. The resulting signed hash becomes the household genesis pin.

Every transition is authorized from the preceding body. `rotate` and
`create_list` preserve the exact roster/recovery principal. `remove` only
subtracts devices/members, preserving remaining roles. `replace_recovery`
preserves the normal roster and replaces the recovery identity, signing key and
recipient key. Neither key may reuse a preceding or historically retired key. `approve_join`
adds exactly one asserted device, preserves retained roles and binds one
unexpired, unconsumed invitation and request. Its member and role must match
the owner's invitation, including a deliberately invited extra owner device.
A new owner role is an explicit owner choice, never inferred from a name.

`transfer` preserves device keys and member IDs. Only the requesting owner's
and accepting member's roles may change; the target becomes an owner and signs
the body hash. `recover` is signed by the preceding recovery principal, retains
that principal, removes every normal device/member and adds exactly one fresh
owner/device with fresh signing and recipient keys. Retired key bytes cannot
be re-registered under new IDs. It cannot add a list or consume an invitation.

All non-genesis transitions increment the epoch by one, rotate every list key
and bind exact current heads and staged snapshots. Normal transitions retain
the exact list set; `create_list` adds exactly one previously unused ID. Package
and snapshot signatures use the preceding authorized signer. Staged candidates
expire in one hour, at most two per list and 42 per household including proposed
lists. Committed history, including retained snapshots, is capped at 100 MiB
per list. No event or tombstone compaction is authorized.

HTTP wrappers remain the OpenAPI names: `ManifestProof` returns only body,
signature and optional transfer acceptance; recipient-scoped packages are read
separately. `Membership.nextEpoch` is the highest delivered epoch to pass as
`afterEpoch` when reading the next page. Snapshot references use `sha256`. Append results use `inputIndex`,
`outcome`, `lastSeq`, `head`; reads use `entries`, `nextCursor`, `hasMore`.
Authentication errors and capacity failures are structured codes. `unavailable`
(503) is retryable without dropping pending intents.

The purpose-bound auth proof has the S0 binary layout. Recovery uses the
registered recovery principal UUID in the existing `deviceId` field. A device
associated with any restored/frozen household cannot obtain a new session;
use fresh device keys when creating a recovery copy. This conservative rule
prevents an origin-wide session from reviving restored authority.

An `intent_exists` error identifies the input and includes the original committed
receipt and envelope ID. If the collision is with an earlier new input in the
same rejected batch, no receipt or envelope ID is returned: that input is rolled
back too. Resolve the stable intent through the authenticated intent route.
