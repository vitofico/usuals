# S0 protocol review record

Base revision: `0594447`. Scope: the S0 contracts, synthetic fixtures and test-only
crypto probes. Production sync is not implemented. This record must distinguish
self-review, independent review and later integration evidence.

## Self-review changes from the initial plan

| Finding | Disposition |
|---|---|
| Re-encrypting an intent after a lost acknowledgement could duplicate it | Separate stable intent id from envelope id; query and compare the original accepted envelope before clearing/rebasing. |
| Two simultaneous captures can allocate different ids and orphan later edits | Deterministic first-live-row ownership plus durable proposed-id redirects; resolve dependent commands through redirects. |
| Membership changes can race an old-key write or an incomplete snapshot | Same membership/list lock order and CAS on every cutoff head; regenerate snapshots if any head changes. |
| HPKE context referring to its final manifest hash is circular | Bind packages to the predecessor; final signed manifest binds package/snapshot hashes. |
| A recovery file containing only old list keys cannot recover future epochs | Separate registered recovery principal; every epoch wraps keys to it; kit replacement revokes the old principal. |
| Room's old fortnight reaper would erase shared tombstones | Scope reaping to local-only lists until a separately reviewed compaction protocol exists. |
| PIECE and PACK share a model dimension but are not interchangeable | Explicitly forbid conversion and preserve incompatible incoming capture as a conflict. |
| Current Android Kotlin cannot consume current Ktor metadata safely | Isolate relay build/compiler and share pure protocol source/fixtures; verify both builds at S2. |
| Existing backup hooks do not establish the proposed RPO | Record Borg/dump capability and require WAL/restore evidence at S5. |
| Inlining every snapshot can exceed the control request limit | Stage encrypted snapshots with TTL/quota; commit exact references atomically with membership. Separate operation, snapshot and control limits. |
| A next-epoch snapshot binding its final manifest hash is circular | Bind predecessor in its header and bind snapshot hash in the final manifest; uncommitted candidates grant no authority. |

## Independent review

At Vito's explicit authorization, a separate read-only review agent inspected
all S0 contracts, OpenAPI, fixtures and test/build deltas. It performed no edits,
builds, service/device access or VCS writes. It found seven actionable gaps:

| Priority | Finding | Resolution |
|---|---|---|
| P1 | Bootstrap/list snapshot staging depended on an already-created list | Empty roster-only genesis; household-authorized staging; CreateList is an epoch-advancing membership CAS that binds every package and snapshot. |
| P1 | Recovery had no consistent API authority | Distinct five-minute recovery session with explicit read/stage/CAS capabilities; ordinary writes/invites denied; package-free manifest proofs and own-recipient package reads. |
| P1 | A stale roster could reauthorize a revoked device after restore | Independent membership pins and durable restore fence before authentication; old household never resumes; verified recovery copy uses a fresh household and keys. |
| P2 | Genesis included its own derived fingerprint | Zero genesis/predecessor fields for genesis hashing; derived hash required afterward. |
| P2 | Pending joins omitted the proof and did not bind the recipient key | Exact signed join assertion binds both keys, pins, member/device/invite, nonce and expiry; PendingJoin returns it unchanged for local verification. |
| P2 | Late repeat operations could outlive their rule or orphan dependent edits | Validate current rule/effective purchase for new occurrences; accepted duplicate redirects occur first; regression scenarios cover both. |
| P2 | Mixed append retries/conflicts had no atomic failure/response rule | All-or-none new writes, per-input accepted/duplicate receipts, rollback on any invalid/conflicting input. |

Follow-up review confirmed all seven findings closed and no remaining actionable
S0 contract gaps. It explicitly left reducer, migration, authorization, crypto
integration and restore execution to their S1 to S5 gates. This review is not a
cryptographic audit or evidence that the future relay is implemented.
## S1 repeat-cycle follow-up

The implementation exposed a conflict between S0's permanent repeat occurrence
and the settled local behavior of deferring a swiped repeat until the next shop.
The same independent reviewer recommended an explicit new cycle bound to the
accepted deletion and next effective Finish. Base occurrence hashes stay stable;
accepted old cycles always remain no-ops with redirects.

The first delta review found two gaps: a cancelled ancestor could bypass a
newer deletion, and legacy rules could borrow another identity's occurrence.
Added authoritative cycle heads, immutable base hashes and guarded Undo rollback.
Nine new reducer scenarios cover re-arming, duplicate redirects, cancellation,
edited rows, lineage and cross-identity denial. The follow-up review closed both
findings with no further actionable gap in this delta. Main-agent Docker and
emulator verification remains the S1 acceptance gate.

## S2 concrete membership and HTTP follow-up

The same authorized independent reviewer inspected the concrete serialized
membership body and its authority/retry enforcement. This was a bounded
follow-up to S0, not an implementation delegation or cryptographic audit.
It identified these mismatches and the final recheck closed them all:

| Finding | Resolution |
|---|---|
| Recovery replacement/re-enrolment did not explicitly require both fresh public keys | Require fresh signing and recipient keys as well as IDs. Immutable, historically unique PostgreSQL principal keys provide an additional guard. Denial tests cover reuse and old-kit access. |
| Documented required fields could be omitted, and optional fields accepted null | Check required signed-body keys before decoding; reject protocol nulls, unknown fields, duplicate decoded keys and bounded structure. Signed denial cases exercise missing defaults and explicit null. |
| Package reads omitted the required list filter | Require `listId` and positive `epoch`; authorize recipient and household, filter exactly that list. HTTP and recovery tests cover scope. |
| `intent_exists` omitted the durable original receipt | Return input index, existing envelope ID and committed receipt. Suppress receipts allocated by an earlier input in the same rejected transaction. Regression tests verify rollback and both cases. |
| Membership writes returned the proof without its response wrapper | Return the documented `Membership` body for creation and transitions. |
| Creation responses used 200 rather than 201 | Explicit Created statuses for household/list creation, with HTTP assertions. Membership changes remain 200. Other route statuses/bodies also match OpenAPI. |

The review performed no builds, edits, services, devices or VCS writes. Main
agent PostgreSQL/HTTP/WebSocket tests remain the implementation evidence. Android
key protection, verified joining, real encryption and sharing integration are
still the separate S3/S4 gates.

## S3 Android cryptography and continuity follow-up

The same authorized independent reviewer inspected only the new cryptography,
wrapped state, household client and its shared membership rules. Final source
recheck closed these findings; main-agent tests remain the execution evidence.

| Finding | Resolution |
|---|---|
| A raced/expired pending membership could block the owner indefinitely | Resolve exact acceptance first, allow only proven unusable changes to be cleared, and restage expired ciphertext under fresh references. Retain counters and shopping work. |
| Numeric monotonicity did not prove extension of an acknowledged head | Persist list heads and bridge ciphertext receipts to signed cutoffs/snapshots, including the exact cached head while replaying older snapshots. |
| Cancellation could forget a verified foreign membership | Commit its proof, bridged pins and removed status with pending-state removal. |
| Recovery renewal could persist the temporary recovery identity | Explicit restricted-token reads, with normal-session helpers refusing recovery identities. |
| An interrupted recovery could be stuck after another owner's change | Verify the new epoch, preserve continuity, and rebuild only after the old CAS cannot commit. |
| Same-household joining could discard an existing identity | Refuse replacement; fetch keys before one atomic join completion. Injected key-read failure remains retryable. |
| Replacement kits could exceed producer/reader crypto limits | Align decoded/encoded bounds with framing room; test a legitimate chain above 64 KiB and the exact accepted boundary. |

An existing cached head that restricted recovery cannot bridge intentionally
fails closed with local state retained. This capability limit is documented in
[android-keys.md](android-keys.md). This follow-up is not a cryptographic audit,
cluster approval or a substitute for the S4 sharing/device gate.

## S4 data-plane follow-up

The same authorized independent reviewer examined the receipt/confirmation,
recovery-fork and explicit merge changes. Follow-up checks closed durable append
witness continuity, same-id source collision batch blocking, merged-row
incarnation fencing and canonical alias guards. Recovery must include the exact
cached membership epoch/hash even when the returned chain is longer.

The merge command is part of the unpublished schema-1 freeze. Changing its
semantics after publication requires an explicit schema/capability transition.
Main-agent Docker and native emulator checks remain the execution evidence.
This bounded review does not authorize deployment or claim a physical pass.

The final bounded S4 recheck also closed normal-session context loss across
pages and stale saves overwriting renewed sessions. Authorization carries an
explicit normal identity; restricted recovery tokens cannot become normal
contexts. Matching origin/server/device saves preserve the latest durable
session. A real HTTP test catches up two missed epochs with exactly one renewal.

Private-first local deletion retains a confirmed retry path for orphaned shared
Room scopes after enrollment was already forgotten. The native pass exercised
that exact interruption and restart. Final independent recheck found zero
remaining findings in this delta. All execution remains main-agent evidence.

## S5 implementation review, 2026-10-16

The separately authorized reviewer inspected the concrete frozen-copy and
operations delta read-only. Final bounded recheck: zero remaining blockers.
Closed findings: historical cutoffs cannot prove receipt ancestry to a later
snapshot; preserve the chosen copy name; permit verified-kit facts recovery with
unreadable cached state and an ancestry warning; exclude tombstoned preview rows;
open grants relative to a protected directory handle; atomically publish complete
exports without overwrite; authenticate bulk headers before body admission;
identify clients only through an explicitly trusted immediate ingress hop; create
an absent external restore fence without replacing the receipt identity; and
retain permanent frozen-id/revocation guards during content purge.

The final rollback recheck also closed restore-fence ordering. Normal startup
rejects unknown or modified migration ledgers. Explicit restoration checks the
schema and receipt identity before creating or replacing the external fence;
regressions cover both existing and missing fence files without mutation on
preflight failure. The stopped-traffic precondition remains required.

The reviewer did not execute tests or access a cluster/device. Docker/native
verification is recorded separately in [release checks](../release-check.md).
Live deployment, physical testing, off-site recovery and an elapsed week pilot
remain explicit acceptance gates, not outcomes of this source review.

## Local deployment-tool follow-up, 2026-10-18

Independent bounded review covered the bundle renderer/verifier, nonsecret
inventory, Docker resource-profile wrapper and explicit JVM load client. No
protocol, relay production behavior, secret format or cluster configuration was
changed. All findings were closed on source recheck:

- Explicit snapshot crypto bound lets the opaque near-HTTP-limit fixture reach
  authenticated relay admission instead of failing at the normal operation limit.
- An explicit optimized-Python rejection preserves all validation/integrity gates.
- Namespace and qualified-label syntax match Kubernetes; scoped and mapped IPv6
  CIDRs are rejected to match the trusted-ingress representation.
- Private load-client setup follows the wrapper build inside cleanup handling;
  cleanup only removes a successfully created directory, preserving collisions.

The renderer retains fail-closed candidate markers, separates operator jobs and
checks Linux/amd64 image architecture. PostgreSQL uses a repository-qualified
platform manifest, the profile/drill verify the runtime architecture, and image
archives select amd64 explicitly. Engine image IDs and platform manifest digests
are recorded separately from content-derived local tags. Hash verification rejects
altered artifacts and symlinked artifact paths. No deploy/import/push/SSH/Kubernetes operation exists in
the preparation flow. The load fixture targets only a disposable local relay;
claims remain bounded to one household and synthetic opaque snapshots. Functional
Docker and local validation evidence is recorded in the release checklist.
