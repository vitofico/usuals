# Android keys and household enrollment

S3 connects real Tink encryption to household enrollment and Android Keystore.
[Shopping synchronization and its worker](android-sharing.md) connect Room in S4. The relay is still local;
cluster deployment requires a separate approval.

`ServiceLocator` constructs `SyncVault`, `VaultSyncStateStore`, the bounded
OkHttp transport, the initial-list checkpoint source and `HouseholdClient`.
Settings opens household enrollment. Invitation links and QR codes enter this
screen without entering grocery capture. Verification QR decoding is separate
from GTIN decoding and never inserts an item.

## Private state

A platform Keystore AES-GCM key wraps private device signing/HPKE keys, list
keys, sessions, pending membership transitions and encryption reservations in
an atomic file under `noBackupFilesDir`. There is no plaintext fallback. A
missing or invalid wrapping key leaves the file untouched and reports locked
keys. Shopping Room data, shopping archives and Android transfers contain no
private identity, recovery key, bearer secret or routing configuration.

Reserve an encryption use durably before generating a nonce. Interrupted
attempts consume their reservation. The 4,096-use limit applies per device,
list, epoch and key fingerprint. Membership retries reuse saved ciphertext.
Expired candidates may reuse those bytes under fresh candidate references;
references and the signed manifest are regenerated, without resetting counts.

The Android runtime uses one Tink artifact, `tink-android` 1.23.0. The isolated
server uses Java Tink 1.23.0. Android Security Crypto's former transitive Tink
1.8 artifact is upgraded by dependency resolution. An actual two-install
emulator test wrote a synthetic provider key with 1.8 and read it after upgrading
to 1.23. Original preferences were backed up and restored.

## Enrollment and continuity

Before creation, save the encrypted recovery file and its separate random
256-bit secret, then reopen and verify both. Recovery signing and HPKE keys are
self-tested; only public recovery material remains in household state.

The applicant signs both device public keys, the exact invitation, household,
server/genesis pins, challenge and expiry. The owner must scan the applicant's
reverse QR or enter the entire fingerprint obtained through a trusted channel.
The applicant verifies the complete signed membership chain and its exact
approved assertion before receiving its own recipient packages. Identity,
keys and removal of the pending join are committed together. Failed key reads
leave joining retryable. A second join/bootstrap cannot replace an existing
local identity, keys or pins.

Phones retain the highest verified membership hash/epoch and list heads in
wrapped state. A larger sequence alone is insufficient: ciphertext receipt
chains must bridge the known head to a newer signed cutoff or snapshot. A
snapshot before the cached head must replay through that exact head. Unsupported
payload versions and unverifiable extensions fail closed.

## Recovery and ownership

A current owner can replace the recovery principal only after saving and
verifying a new encrypted kit. Replacement kits carry the genesis chain and an
owner-signed `USUALS/RECOVERY-REPLACEMENT/1` statement binding origin, server,
household, genesis, predecessor and new recovery public keys. The server's
current signed roster must still authorize that recovery principal when used.
The old kit cannot recover after replacement.

Kit plaintext is at most 6 MiB minus 128 framing bytes, reserving space for the
AEAD tag, nonce, base64 and JSON under the 8 MiB file ceiling. Producer and
reader bounds match, including an exact-boundary regression.

Recovery uses explicit restricted tokens. Recovery private material never goes
through normal-session persistence, including a misleading near-expiry session
response. Recovery enrolls a fresh normal owner and revokes all old normal
devices. An interrupted recovery can resume or rebuild against a verified
newer epoch without resetting reservations or losing pending shopping work.

An existing cached head can precede the current snapshot cutoff. If restricted
recovery cannot read the receipts needed to bridge that head, recovery fails
closed and retains the complete prior local state. It does not silently clear
pins. Fresh-install recovery has no prior head and verifies the kit's genesis
chain and current encrypted state. A verified operator recovery copy is needed
when a retained head cannot be bridged.

Ownership transfer preserves accepted device keys and requires the selected
recipient's separate signature over the exact proposed manifest bytes. The
recipient verifies authority and known-head extension before acceptance. The
owner binds that acceptance in the final membership signature.

A pending change is cleared only if it was never capable of committing, a
verified newer membership supersedes it, or an authenticated advanced cutoff
makes the signed old snapshot unusable. A possibly accepted change is resolved
by its exact manifest hash first. Verified foreign membership and head pins are
saved atomically when clearing an outdated proposal.

## Verification

Real local PostgreSQL/HTTP tests exercise two clients, QR/fingerprint approval,
lost acknowledgements, key-fetch interruption, stale snapshot recovery,
candidate expiry, head rollback/fork rejection, accepted ownership transfer,
kit replacement, fresh-install recovery and old-device denial. API 26 and 33
Robolectric fixtures exercise the selected crypto APIs. The 16 KB API 37.1
emulator runs actual Keystore wrapping, deletion/invalidation, durable counter
and encrypted-exchange tests. This does not claim an actual API-26 hardware
pass or a physical-phone sharing pass. The optimized release APK also runs a
valid encrypted kit through AES-GCM decryption, signature verification and HPKE
self-tests to the expected offline transport boundary. Wrong keys fail before
network access, and wrapped state reopens after process restart. Recovery
feedback stays visible after scrolling to the system file picker.
