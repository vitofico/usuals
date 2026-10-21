# Security and membership v1

Status: contract, not deployed. Protect shopping contents from a relay/database
operator and network observers, assuming uncompromised member devices and
verified invitation keys. An authorized editor can read/copy all shared data
and issue valid edits; no encryption prevents that. The relay can deny service
or fork/stale a new device's view. Known heads detect rollback for an existing
device; they are not a global transparency system. No forward-secrecy claim.

## Browser asset trust

The Android client above protects contents from the relay operator without
executing code supplied by that operator. The optional browser client also
requires trust in its delivered JavaScript and serving origin. An active asset
server can replace executable code and capture an unlocked passphrase or shopping
contents. Browser encryption protects stored/transmitted data while the delivered
client is trusted; it does not provide Android's stronger active-operator boundary.
Restrictive CSP, local assets, safe DOM construction and reviewed interoperability
reduce injection mistakes, but cannot make a hostile asset server trustworthy.
Account sign-in grants no household keys. Browser devices still require verified
owner enrollment and the same signed membership/receipt checks. See
[the browser protocol](../../web/PROTOCOL.md) and [identity](web-identity.md).

## Cryptographic profile

Use Tink 1.23.0 Java primitives, pinned in the version catalog, with no custom
curve, AEAD or random-number implementation. List AEAD is AES256_GCM, RAW prefix;
key packages use DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM HPKE, RAW;
signatures use ED25519, RAW. Distinct signing and recipient keys per device.
Generate keys/nonces with the library's secure randomness. The public RFC 8032
vector in tests is a fixture, never an application key. The test-only library
does not change the Android production dependency graph in S0.

[Tink AEAD](https://developers.google.com/tink/aead) authenticates its associated
data but does not hide it. [HPKE in Tink](https://developers.google.com/tink/hybrid)
provides recipient privacy, not sender identity. Every package and operation
therefore has a verified authorized sender signature. Ciphertext size, list ids,
member/device routing, IP and timing remain visible. Text, quantities, aliases
and purchase history are encrypted. The relay never receives provider keys,
product-cache rows, voice audio, recipe bodies or local suggestion dismissals.

Wrap Tink private keysets and current/retained epoch keys with an Android
Keystore AES key. Fail closed if wrapping fails; no plaintext fallback. Keep
key material and sessions outside Room, JSON shopping archives and all Android
backup/device-transfer rules. S3 must verify Android API 26, release shrinking,
key invalidation and 16 KB compatibility before shipping any of these choices.

## Trust establishment

The operator creates a one-use bootstrap token for the first owner. The phone
records the relay origin and signing key through its authenticated HTTPS
connection. The genesis manifest binds server identity, household, initial
owner/device public keys and recovery public keys, and is signed by the owner.
The household UUID is random routing, distinct from its genesis fingerprint.
Genesis body fields `genesisHash` and `previousHash` are both 32 zero bytes,
with epoch 1; hash the signed genesis once and use that resulting genesisHash
in subsequent manifests/invitations. Household identity is that genesis hash. TLS/origin trust at bootstrap is explicit;
a server claiming another origin is rejected. Do not reuse Quire credentials,
Spunto bearer tokens, Authentik forward-auth or provider keys.

An owner invitation is a pairing v2 link (below). QR reading does not use the
app's grocery GTIN scanner. Applicant generates both keys locally and signs
a join assertion with its signing key. The inviting device verifies proof and
the assertion's MAC under the link key, which the relay never sees. There is no
approve-without-verification path. The applicant checks the owner's fingerprint
from the descriptor the link anchors. The relay cannot replace a public key using
a directory response alone.
Fingerprint bytes are SHA256(`ASCII("USUALS/FINGERPRINT/1\0") || serverId
|| householdId || Ed25519PublicKey32 || X25519PublicKey32`). Public keys are
raw 32-byte values; fixed profile parameters reconstruct Tink key objects.
HPKE context is `ASCII("USUALS/KEY/1\0") || serverId || householdId || listId
|| u64(epoch) || predecessorHash || recipientFingerprint`. Package signing input
is `ASCII("USUALS/PACKAGE/1\0") || bytes(context) || bytes(ciphertext)`.

The join signing input is `ASCII("USUALS/JOIN/1\0") || bytes(originUtf8)
|| serverId || householdId || genesisHash || inviteId || requestId || memberId
|| deviceId || signingPublicKey32 || recipientPublicKey32 || SHA256(inviteSecret32)
|| challengeId || nonce32 || u64(assertionExpiresAtMillis)`. Ids are UUID bytes.
The server requires arrival before the 60-second challenge expiry and consumes
that nonce once; the signed assertion expires no later than the invitation's
expiry (24 hours for v2). Pending owner responses return the exact assertion,
signature and MAC, never the secret. The owner verifies signature, origin/pins,
expiry, all identities and both key bytes, then the MAC with its stored link key.
Approval binds the assertion hash/request id into the signed manifest; the
applicant verifies that binding and its keys before accepting membership.
A generic session proof does not substitute for this join assertion.

### Pairing v2

The owner draws a random 32-byte link key `k` and derives, with HKDF-SHA256 and
an empty salt, `admit = HKDF(k, "USUALS/INVITE/2/admit")` and `macKey = HKDF(k,
"USUALS/INVITE/2/mac")`, 32 bytes each. It uploads the descriptor, the exact
UTF-8 JSON `{version:2, origin, serverId, householdId, genesisHash,
ownerDeviceId, ownerFingerprint, memberId, role}`, plus `SHA256(admit)`, and keeps
`k` in its wrapped state per outstanding invite (Android) or vault (browser).

The link is `https://<origin>/join#2.<b64url(inviteId16 || k32 || anchor16)>`
with `anchor = SHA256("USUALS/INVITE/2\0" || descriptor)[0..16]`; the app form is
`usuals://join#` plus the base64url link. The applicant opens the invite with
`admit`, checks the anchor and that the descriptor's origin is the link's origin,
then sends `mac = HMAC-SHA256(macKey, "USUALS/JOIN-MAC/2\0" || assertion)` with
the unchanged `USUALS/JOIN/1` assertion. Both screens may show `uint32be(HMAC(
macKey, "USUALS/JOIN-CODE/2\0" || assertion)[0..4]) mod 10^6`.

The relay knows `admit` but not `k`, so it cannot produce a MAC over substituted
keys, and it cannot swap the household or owner without breaking the anchor.
Whoever holds the link can ask to join; the owner's tap, single use and the
24-hour expiry bound that. The server connection code is likewise
`uint32be(fingerprint[0..4]) mod 10^6` of the existing connection fingerprint.

Join requests consume an invitation only when approval commits; rate-limit
requests and expiry prevent unbounded pending state. Denial/cancellation may
invalidate the invitation. A concurrent second approval loses the CAS and must
not enrol a second member. Owners approve each extra device separately. A
member's new device must not inherit owner status through a display name.

### One-scan connection offer

A signed-in browser draws a random 32-byte offer secret and registers
`SHA256(secret)` for its account; the offer lasts 10 minutes and an account holds
at most five unclaimed offers. An unlocked owner browser also creates a pairing v2
invite. The QR is `https://<origin>/connect#1.<b64url(offerId16 || secret32
[|| inviteId16 || k32 || anchor16])>`, and the app form is `usuals://connect#` plus
the base64url link. The phone sends `{offer: {id, secret}}` with its connection
start; the relay binds the new ticket to an unexpired, unclaimed offer whose hash
matches, once, and the offer then expires with the ticket. Only the offer's
account can read it, and so learn which ticket to approve. After approval the
phone joins with the embedded invite under the unchanged v2 rules, signing with
the same device keys it sent in its connection start. The browser approves
without a second code only a request whose MAC verifies under that invite's key
and whose device keys reproduce the ticket's connection fingerprint, so a request
made from a photographed QR still needs its join code compared. The offer secret
grants account access, which the relay controls anyway; the invite key never
reaches it. Reusing the keys lets the relay link that household device to the
account, which it could already infer from timing.

## Manifest and rotation transaction

A signed full membership manifest contains version, household/genesis/server,
epoch, previous manifest hash, members/roles, device signing and recipient keys,
recovery public keys, affected list ids, per-list old head cutoffs and hashes of
every new encrypted snapshot/key package. Sign length-framed domain
`USUALS/MEMBERSHIP/1\0` and exact UTF-8 manifest bytes; no JSON reserialization.
Reject duplicate keys, ids, duplicate public keys, missing packages, unknown
roles, an empty owner set or a previous hash/epoch mismatch. Both relay and
clients check authority against the preceding manifest, never against the new
manifest's claimed owner. The manifest body names its signer device/principal.
For transfer, the already accepted recipient device signs
`ASCII("USUALS/TRANSFER/1\0") || SHA256(manifestBytes)`; place that signature
in `transferAcceptance` outside the manifest body. The owner signs
`ASCII("USUALS/MEMBERSHIP/1\0") || bytes(manifestBytes)
|| bytes(transferAcceptanceSignatureOrEmpty)` and the manifest hash is SHA256
of that input followed by the owner signature. This avoids self-referential
acceptance signatures and binds the accepting device named in the body.
Genesis uses a zero predecessor and epoch 1. Recovery authority is specified below.

Household creation commits an empty genesis roster, with no lists, packages or
snapshot references. The owner can then stage a snapshot for a proposed new
list UUID at a household-scoped endpoint. CreateList consumes the candidate
through an owner-signed membership transition with `action=create_list` and
one `createdListId`. It increments the epoch, rotates existing list keys and
initializes the new list at head 0/zero. The exact affected list set is all
existing active ids plus the one declared, previously unused new id. Existing
heads must match; the new list cutoff is 0/zero. This uses the same committed
manifest as the authority for every package, including the new list's packages.
Concurrent list creation or membership changes force a CAS retry. This avoids
both the staging dependency and an unsigned list-creation authority.

Before changing membership, an owner catches up every list, builds fresh epoch
keys and snapshots at exact heads, and wraps each key for every remaining
device plus recovery recipient. HPKE context binds server, household, list,
epoch, manifest predecessor and recipient fingerprint. Sign packages separately
over context and ciphertext, then bind their hashes into the manifest. Avoid a
circular signature: packages bind the predecessor; the final manifest binds
their hashes. Clients accept packages only through that final committed manifest.
Next-epoch snapshot headers also bind the predecessor manifest hash, avoiding
the same circular dependency. Their final hashes must occur in the committed
manifest before clients accept them. Current-epoch snapshots bind the current
manifest hash. A candidate upload grants no membership or log authority.
Upload snapshots to bounded staging rows first; reference their id/hash/head
in the membership transaction. Expire unused candidates and never accept a
candidate for another household, list, author or epoch.

Under the protocol lock order, relay CAS checks all old heads and current
manifest hash, inserts manifest/packages/snapshots, updates epoch, and revokes
sessions/sockets for removed devices atomically. Appends racing before that
transaction belong to the old cutoff and force snapshot regeneration if they
changed a head. Appends after it require the new epoch. There is no interval
where a removed device can append under an accepted new manifest.

Every membership change rotates all existing list keys, including invitations,
to give new members a current snapshot without old epoch keys. Removed members
can keep any plaintext or old keys already obtained; rotation protects future
changes only. Retained devices first resolve ambiguous intent ids, then rebase
and re-encrypt unsent intents with new envelope ids. Removed devices keep local
unsent work for explicit export; no automatic upload through another identity.

## Recovery and restore

A recovery proof issues a distinct five-minute recovery session, never a
normal member session. It may read its household manifest chain/list ids,
current snapshots, current-epoch operations and only key packages addressed to
its recovery fingerprint. It may stage next-epoch recovery candidates and
submit exactly a recovery CAS transition: remove all normal devices, enrol one
new owner device, preserve the active recovery principal, rotate every list
key and bind all current heads. No ordinary append, invite, list creation or
arbitrary ownership mutation. The relay and clients enforce this shape against
the predecessor. Add `GET /v1/households/{h}/key-packages` scoped to the caller's
recipient fingerprint, epoch and list; normal devices receive only their own.

At household creation register a separate recovery signing/HPKE principal in
the owner-signed manifest. Every epoch includes key packages to this recipient.
The recovery kit contains server/genesis pins and recovery private keysets,
encrypted with a freshly random 256-bit recovery secret encoded for offline
storage. No human password or password-derivation scheme in v1. Save the kit
and secret separately through explicit UI, then verify decrypt/sign/decrypt-key
round trips before erasing temporary recovery private keys from the phone.
The phone retains only the public recovery recipient and wrapped list keys.

Recovery requires the kit and secret. A recovery signature proves possession
to a purpose-bound challenge; the new device verifies the complete current
manifest chain from its genesis pin and decrypts current recovery packages.
It proposes a CAS recovery transition signed by the active recovery key that
revokes all old normal devices, enrols a fresh owner device and rotates all list
keys/sessions. If another active owner changed the recovery principal, the old
kit is invalid. Replacing a kit is an explicit current-owner rotation with a
new recovery principal and verified new kit. A server administrator cannot
reset keys or grant household membership. Loss of every owner device and the
active recovery kit makes encrypted contents unrecoverable.

A database-only phone restore never restores a device private identity. Its
shared lists are local retained copies until re-enrolment proves membership;
they must not upload as the lost device. Encrypted server backup also does not
contain recovery secrets. Clients pin the highest membership epoch/hash as
well as every acknowledged list head. A revocation without a shopping edit
must still be detected if the roster rolls back.

V1 server restore is fenced. The restore command stops traffic, invalidates all
sessions/challenges/invitations and writes a fresh restore fence outside the
restored database before serving any authentication. Every restored household
is permanently frozen for normal sessions, new challenges, append and recovery
CAS, including a revoked device requesting a fresh challenge. Do not resume
its old identity from database rows or list-head equality. A trusted operator
may issue a separate, local recovery-export grant to the known owner, bound to
an out-of-band verified key fingerprint; it allows only encrypted archive/key-
package retrieval. That grant neither asserts current membership nor adds an
owner. The key-holding phone/kit decrypts locally, reconciles retained data and
creates a reviewed copy in a fresh household with new keys and fresh invites.
The old household stays fenced. No automatic cursor reset or old-device
re-enrolment. This intentionally trades automatic restore for preserving
revocation when current membership evidence was lost. S5 must implement and
exercise the fence before any live pilot; no restore command exists in S0.

## Required adversarial evidence

S0 tests pin framing, an RFC signature vector, Tink AEAD tamper/AAD rejection
and HPKE wrong-recipient/context rejection. They do not certify this protocol.
Independent review must cover key substitution, owner/recovery privilege,
concurrent membership CAS, stale-epoch acknowledgement loss, replay/cross-list
injection, snapshot cutoffs and rollback. S2/S3 integration tests must execute
the denial cases in `security.json` against real auth, persistence and crypto.
S4 must verify permissions, process death and two devices on the actual UI.

Membership GET returns proof records containing exact signed manifest bytes,
signature and optional transfer acceptance only. The signed bytes retain all
snapshot/package hash references. Package ciphertext is fetched separately only
for the caller's recipient fingerprint; a recovery session cannot obtain other
recipient bodies through a manifest response. Full package/snapshot-reference
completeness validation applies to transition submission, not proof responses.
