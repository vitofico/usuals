# Shared-list protocol v1

Status: S0 contract for implementation and independent review. All examples are
synthetic. Normative MUST/REJECT rules here take precedence over the earlier
planning sketches. A single household has named lists, owners and editors;
every member can edit every list. Accounts are pseudonymous member ids, with
one or more device keys. Local-only lists never register or upload.

## Framing and identity

Transport is HTTPS JSON, with unpadded base64url for binary values. UUIDs use
16 network-order bytes inside signed headers and canonical lowercase strings
in JSON. Integers in the binary format are unsigned big endian, bounded to
signed-64 range. `bytes(x)` is u32 byte length followed by x, not a character
count. Text is strict UTF-8. Reject malformed UTF-8, duplicate JSON keys,
trailing binary bytes, unknown header versions and oversized values.

There is no JSON canonicalization requirement: encrypt once, persist the exact
envelope, and retry those bytes. Never reserialize an acknowledged envelope.
The fixed binary header in this order is:

| Field | Encoding |
|---|---|
| Protocol | u16, exactly 1 |
| Server identity | 32 bytes, pinned relay signing-public-key fingerprint |
| Household, list, author device, intent, envelope ids | Five UUIDs, 16 bytes each |
| Membership epoch | u64, starts at 1 |
| Membership manifest hash | 32 bytes |
| Known base sequence and head | u64 and 32 bytes; genesis is 0 and 32 zero bytes |
| Payload schema | u16, exactly 1 |

AEAD associated data is `ASCII("USUALS/AAD/1\0") || bytes(header)`.
The Ed25519 signing input is `ASCII("USUALS/ENVELOPE/1\0") || bytes(header)
|| bytes(ciphertext)`. The body is AES-256-GCM RAW output:
12-byte nonce, ciphertext, 16-byte tag. No Tink key-id prefix goes on the wire.
The signature is the 64 raw Ed25519 bytes. Header ids MUST match the URL,
authenticated device and verified membership, including list ownership.
No plaintext name, canonical item key or plaintext digest appears in a header.
The header is exactly 196 bytes. Operation ciphertext is at most 65,276 bytes,
leaving 260 bytes for header/signature within the decoded 64 KiB ceiling.
Snapshots use the same framing with a separate upload/body limit.

The relay adds `(seq, previousHead, head, receiptSignature)` after committing.
`head = SHA256(ASCII("USUALS/HEAD/1\0") || previousHead || u64(seq)
|| SHA256(signingInput) || signature)`. Receipt signing input is
`ASCII("USUALS/RECEIPT/1\0") || serverId || householdId || listId
|| u64(seq) || head`. Clients pin the receipt key, verify contiguous heads and
retain their highest acknowledged head. A base head must exist on that list,
and the assigned sequence must be greater than its base. Also pin the highest
verified membership epoch/hash; equal list heads do not prove an unchanged roster. The server sequence
is ordering metadata, never a client's physical time or proof of availability.

## Durable append and catch-up

In one PostgreSQL transaction, lock membership, then affected list rows sorted
by UUID; check epoch and base head; allocate `last_seq + 1`; insert envelope
and head; update the list cursor. Return success only after commit. A failed
transaction consumes no cursor. Never use a pre-commit database sequence as
the catch-up cursor. Membership changes use the same lock order.

The unique keys are `(listId, envelopeId)` and `(listId, intentId)`. An identical
envelope retry returns its original receipt without another sequence. A reused
envelope id with different bytes returns `envelope_conflict`. A new envelope
for an existing intent returns `intent_exists` and the original receipt; it
never replaces the original. The author fetches and decrypts that envelope
and compares it with its retained intent before clearing the outbox. Different
contents are a protocol conflict. A stable intent id survives re-encryption;
every fresh encryption gets a new envelope id. Revoked callers receive no
receipt or ciphertext. Retained authors keep old keys until ambiguous retries
are resolved. Query an intent before rebasing or re-encrypting it.

Append batches are all-or-none for new writes. Validate every signature, header,
limit and id conflict before commit under the current membership lock. Exact
retries may coexist with new envelopes and return original receipts; input
order determines new sequence order. Coalesce identical repeated envelopes in
a request, with a result for each input index. Different bytes for one envelope
or intent in the same request reject the entire batch. Any stale epoch, conflict,
quota or invalid input rolls back all new writes; existing receipts remain
unchanged and may be resolved separately. Success returns per-input index,
intent/envelope id, `accepted` or `duplicate`, receipt and current list head.
The applied cursor advances only through contiguous catch-up, never straight
to a receipt that may skip other members' earlier operations. Retain acknowledged
pending envelopes until catch-up materializes them in the confirmed projection.

The local mutation and pending intent commit in one Room transaction. On receipt,
apply confirmed contiguous operations, then replay optimistic pending intents
over the confirmed state. Losing an HTTP acknowledgement cannot lose a write.
Persist cursor, received ledger, conflicts and projection in one transaction.
An authenticated full checkpoint can atomically replace confirmed projection and
cursor before rebasing durable source intents. Only exact source-intent equality
acknowledges an outbox entry. A same-id payload collision stays reviewable but is
excluded from uploads so it cannot block unrelated edits. Persist validated
append receipt witnesses behind Keystore before further network reads; do not
acknowledge Room work until contiguous catch-up covers those witnesses. Cached
confirmed projections can skip snapshots only at the exact private head,
membership epoch and manifest hash. Optimistic projections never qualify.
Unknown protocol/normalizer/payload versions stop catch-up with upgrade-needed;
do not advance past them. A known-version invalid operation is a deterministic
no-op with a rejected-intent record and code. Its authenticated envelope remains
in the hash chain. Never expose raw remote diagnostics as UI text.

## Payloads and revisions

Payloads are versioned JSON inside encryption. A revision is the accepted intent
UUID that last changed a field, or null for an absent field. A batch is atomic
unless its command explicitly defines per-row outcomes. Wall-clock timestamps
are facts for display and purchase dates; they never choose conflict winners.
All commands carry the intent id also present in the header.

- `capture`: new item id, explicit identity key, name/raw text, optional quantity,
  note/GTIN/store, normalizer version and source. Match only an unchecked live
  identity. First accepted item id wins; later captures record a redirect from
  their proposed id to that id, so their dependent edits still find the row.
  Add explicit compatible quantities once per intent; null adds no quantity.
  Incompatible dimensions, including PIECE versus PACK, retain the original and
  create a conflict containing the incoming request. A checked identity gets a
  new item id. A tombstoned proposed id cannot be reused.
- `edit`: item id/incarnation, changed fields and each expected field revision.
  Independent fields merge. If any expected changed-field revision is stale,
  reject the whole edit and retain both versions as a conflict. A name/GTIN/
  alias change is one identity transaction, never half-applied.
- `merge`: explicitly confirmed source and destination row ids, full expected
  field/lifecycle revisions for both, expected alias revision and the exact
  source identity/GTIN alias delta. Keep the destination quantity unchanged,
  tombstone the source and redirect identity lookup. Guarded mutations continue
  to address the tombstoned source incarnation, including after local reaping.
  Stale previews become visible conflicts. No purchase or history rewrite.
  This command is part of the unpublished v1 freeze; future semantic additions
  require a payload schema/capability change before deployment.
- `check`: expected check revision, checked flag, explicit checkedAt or null.
  `delete`: expected lifecycle revision, then a durable tombstone. A command
  against a deleted/closed incarnation cannot resurrect it. Re-add uses a fresh
  id; resolving a stale edit also requires a fresh intent and current revisions.
- `preference`: expected revisions for shared category/repeat settings.
  `list_name`: expected name revision and new encrypted name; any editor may
  rename an existing list. Only an owner may create a new list, through the membership CAS transition
  defined in security-v1.md, with snapshots/packages for all existing and new lists.
  `alias`: expected alias-map revision and complete proposed delta. Reject cycles
  and a collision of two live identities unless the person explicitly supplied
  the row ids and quantities to merge. No fuzzy automatic identity merging.
- `finish`: trip id, closedAt and exact checked row ids, lifecycle/field revisions
  and frozen purchase facts. For each row, all revisions and facts must match
  the current projection; otherwise leave it open and report a conflict. Close
  the matching subset atomically. First accepted close claims the item; a later
  close that finds it already purchased is a no-op for that row. Zero accepted
  rows creates no trip. Purchase id is item id within the list, boughtAt is
  checkedAt, and final name/amount/GTIN/version come from the matched state.
- `undo`: exact finish intent and trip id. Cancel only that close's purchases;
  restore rows only if their lifecycle still belongs to that close. Do not
  overwrite subsequent mutations. A second Undo is a no-op. A later close of a
  restored item is permitted only after the prior purchase was cancelled.
- `repeat`: deterministic occurrence id = SHA256 of length-framed list id,
  canonical identity, rule revision and effective due purchase id (or the rule
  creation intent if no purchase exists). Two phones create one occurrence.
  Recompute the occurrence id from its tuple first. If that exact tuple was
  already accepted, return a no-op and redirect the losing proposed id to its
  winning row, including a later closed/tombstoned winner; never resurrect it.
  Only a new occurrence validates the current rule revision, a still-effective
  enabling purchase and absence of the canonical live identity before insertion. A stale/removed rule
  or cancelled purchase rejects the repeat, even if its envelope arrives later.
  Dependent edits resolve these redirects and still respect the target lifecycle.
  An explicit delete defers that occurrence until the next effective Finish.
  Re-arming is a new cycle, whose occurrence hash appends four length-framed
  strings to the original tuple: `cycle/1`, prior occurrence hash, its accepted
  delete intent and the first subsequent effective Finish intent in log order.
  The payload carries those three dependency ids as `cycle`. Validate that the
  prior occurrence belongs to the same canonical identity, rule revision and
  enabling purchase, that its winning row still belongs to that accepted delete,
  and that the named Finish is the first later uncancelled close. Apply the
  already-accepted exact-tuple no-op/redirect check before those current-state
  validations, including for a re-armed cycle. Never clear old occurrence records.
  Persist an authoritative cycle head per immutable base tuple. A new child
  must name that head, and its parent must carry the same immutable base hash.
  Cancelling an earlier Finish cannot bypass a surviving or explicitly deleted
  child by deriving a sibling from an older deletion. Roll a head back to its
  parent only when Undo actually retracts that unchanged, still-live automatic
  head. Retain that lineage when local Room tombstones are reaped.
  Two phones derive one cycle. If that enabling Finish is undone, retract only
  the unchanged re-armed row. An edited row survives. A later effective Finish
  can justify a different cycle after the cancellation; a late old cycle cannot
  resurrect its winner. Base occurrences keep the original four-field hash.
  A cancellation/Undo of its enabling close retracts only an unchanged automatic
  row. Explicit edits survive. Rules add no inferred quantity. Local snooze and
  mute suppress suggestions locally; shared capture dedupe still applies.
- `import`: validate a whole bounded batch before changing state. Resolve item
  changes through expected revisions, never archive timestamps. Immutable active
  purchase facts may only repeat identically. Conflicting facts reject the
  batch. Oversized archives become independently reviewed batches, never a
  partially acknowledged import disguised as one action.

Amounts use finite non-negative IEEE-754 binary64 with the existing unit enum;
the wire carries `Double.toString()` decimal strings, parsed without rounding
or locale rules. Calculate in accepted log order, convert kg to g and l to ml
using the existing factors, and reject overflow. PIECE and PACK never convert.
S1 fixtures must cover fractional arithmetic as well as whole numbers.
Normalizer changes need a protocol compatibility decision and explicit rewrite
operations. Do not normalize the same remote text with a different local pack.

Purchase uniqueness is `(listId, itemId)` for the effective history. Cancelled
closes remain in the operation log but are absent from effective purchase
history. Redirected capture ids resolve before close and Undo. A conflict is
local to the initiating member's devices, never silently retried as an overwrite.
The synthetic reducer scenarios include expected projection facts and outcomes;
S1 must consume them through its actual reducer, including reconnect/replay.

The concrete signed membership body is specified in [manifest-v1.md](manifest-v1.md).

## HTTP surface and bounds

All `/v1` responses use structured codes; UI sentences belong to `:app`.
Bodies and Authorization headers are excluded from access logs. No credentials
or invite secrets in URL paths/query strings. Invitation links use a fragment
consumed by the Android app; there is no server-rendered invite landing page.

| Route | Contract |
|---|---|
| `GET /v1/server-info` | Public relay identity, receipt public key and supported protocol versions. Bootstrap pins the key through verified HTTPS; invitation pins must match. |
| `POST /v1/devices/challenge`, `/session` | Single-use nonce proof bound to origin/device/purpose, 60-second expiry; session TTL 15 minutes. |
| `POST /v1/households` | Operator bootstrap token, atomic empty genesis owner/device/recovery roster; create lists afterward. Bootstrap token hashes only, one use, 24-hour expiry. |
| `POST /v1/households/{h}/invites` | Owner only, random 256-bit secret, hash stored, expires in 10 minutes. |
| `POST /v1/join-requests` | Invite secret plus signed full join assertion; pending, never automatic membership. |
| `GET /v1/households/{h}/join-requests` | Owner-only bounded pending requests, public keys and proof for fingerprint verification. |
| `POST /v1/households/{h}/join-requests/{r}/deny` | Owner-only denial invalidates that request and its invitation. |
| `POST /v1/households/{h}/membership` | Owner/restricted-recovery-signed CAS transition with complete key packages, checkpoint/snapshot cutoffs, one transaction. |
| `GET /v1/households/{h}/membership` | Current verified chain; all members; removed devices denied. |
| `GET, POST /v1/households/{h}/lists` | Read list routing; owner creation is an epoch-advancing membership CAS covering existing lists and one new list. |
| `POST, GET /v1/lists/{l}/operations` | Append bounded envelopes; fetch by `after` cursor, at most 100 entries. |
| `GET /v1/lists/{l}/intents/{i}` | Resolve an author's ambiguous acknowledgement within current membership. |
| `PUT, GET /v1/lists/{l}/snapshot` | Owner-signed encrypted checkpoint, scope, exact sequence/head and current epoch; never truncate history. |
| `PUT /v1/households/{h}/snapshot-candidates/{c}` | Owner or restricted recovery principal; unpublished authorized current/next-epoch snapshot for a target list UUID, including a not-yet-created owner list; TTL one hour, max two candidates/list. Return id, ciphertext-envelope hash, sequence and head. Membership/list creation references it without inlining the bytes. |
| `GET /v1/changes` | Authenticated WebSocket hints only; each subscription authorized, socket closed on revocation. |
| `GET /healthz`, `/readyz` | No auth, generic liveness/database readiness, no versions/secrets/member counts. |

Codes: `unavailable` 503 (retry while retaining pending data), `unauthenticated` 401, `forbidden` 403, `not_found` 404,
`epoch_stale`, `base_unknown`, `envelope_conflict`, `intent_exists`,
`membership_conflict`, `key_usage_exhausted` 409, `upgrade_required` 426, `too_large` 413,
`recovery_required` 503, `rate_limited` 429 with Retry-After, `quota_exceeded` 409, `invalid` 400.
Cross-household ids return 404 after authentication to avoid enumeration.

Decoded operation envelope ceiling 64 KiB; append at most 100 and 1 MiB encoded
JSON; snapshot at most 10 MiB encoded body; membership request/response at most
8 MiB, one manifest per response page with `afterEpoch`. Membership bodies use
snapshot references rather than full snapshots. Frame ceiling 16 KiB. Per device append
60/minute, household 300/minute, unauthenticated IP 30 challenges/minute and
5 join attempts/minute; burst equals one minute. Max 8 devices/member,
20 members/household, 20 lists/household, 2 WebSockets/device, 100 MiB encrypted
history/list and 1,000,000 encryptions/key epoch. Exceeding a limit preserves
the outbox. Rotate before key-use exhaustion. No tombstone/log compaction in v1.
Reserve a durable per-device/key counter before encryption, including candidates
and abandoned envelopes. Limit each device to 4096 new AEAD encryptions per
epoch; at most 160 normal devices plus recovery keep the household below the
global ceiling even offline. Retry bytes consume no new encryption. Never reset
the counter on crash or restore; re-enrolment rotates keys. Exhaustion leaves
unencrypted local intents pending until an owner rotates the epoch.
Stage candidates in bounded PostgreSQL bytea rows; they are unavailable to
members until a committed manifest/snapshot references their exact hash.
Validate limits on the complete encoded body before allocating/decrypting it.

Auth signing input is `ASCII("USUALS/AUTH/1\0") || bytes(canonicalOriginUtf8)
|| serverId || deviceId || challengeId || nonce32 || u64(expiresAtMillis)
|| bytes(purposeAscii) || signingPublicKey32`. Purpose is one of bootstrap,
session, join or recovery. At bootstrap/join the public key is caller-supplied
and proof-of-possession only; bootstrap token or verified owner approval still
supplies authority. At session/recovery it must match the registered current
key. Consume each challenge atomically. Require canonical HTTPS origin without
userinfo, path, query or fragment; lowercase ASCII host, no default :443.

Foreground change hints trigger durable reads; network-constrained unique
WorkManager work drains pending writes after process death. Background delivery
is eventual. No FCM, foreground-service loop or network dependency for capture.

Recovery sessions have the restricted route/transition capabilities in
[security-v1.md](security-v1.md), not member privileges. A restored household's
fence takes precedence over all auth/session/epoch checks. No public restore
endpoint exists; any recovery-export grant is an explicitly verified local
operator action with no membership authority.

### S3 retry clarifications

An exact signed join-request retry before expiry/consumption returns its original
202 `JoinPending` even though its challenge was already consumed. A changed
assertion/signature or a denied, expired or consumed invitation fails. This
permits process interruption without granting authority twice.

Membership/list creation returns `candidate_expired` with 409 when a referenced
candidate is absent or expired. The failed CAS commits no membership or list
writes. A client may restage the same saved ciphertext under fresh candidate
IDs and regenerate references and signatures; it must never reset usage
reservations. A moved head or epoch requires verified reconciliation instead.
