# Android shared shopping

S4 connects the encrypted client to Room through `SharedReplica` and the
ServiceLocator-owned `SharingCoordinator`. Local edits commit immediately with
an immutable source intent. Foreground sockets supply hints; cursor reads remain
authoritative. Open, resume and reconnect catch up independently of hints.
Connected WorkManager work drains queued changes after process death and provides
an eventual periodic fallback. There is no FCM or permanent foreground service.

## Confirmation and retry

A confirmed Room checkpoint contains only authenticated server state. Optimistic
pending commands never enter an uploaded base checkpoint. Every batch persists
its ciphertext before sending. An uncertain retry keeps those exact bytes;
a verified new epoch reserves a new encryption use and re-encrypts the same
source intent. Batches stop at 100 commands and the ordinary request limit.

Signed append receipts become durable private head witnesses before catch-up
I/O. A reply is not an acknowledgement until a contiguous authenticated replay
contains the exact source intent. Lagging snapshots must bridge every saved
receipt witness. Same-id, different-source collisions remain visible and cannot
block unrelated uploads. Known membership epoch/hash and list heads survive
restart and never reset to make a bad response acceptable.

## Choices and concurrent work

Sharing previews one local list and an initially unticked history option. It
creates a separate encrypted shared copy and keeps the original local list.
Joining creates a separate scope. The picker labels local and shared copies.
Shared aliases, categories, repeat rules and shopping history synchronize;
mutes, snoozes, provider settings, catalog consent and caches stay on the device.

The screen freezes field, lifecycle and alias revisions with the values it
shows. Independent field edits can coexist. A stale edit, check, deletion or
preference becomes a reviewable conflict instead of overwriting a newer choice.
Quantity hints submit the displayed amount. Barcode naming that would merge two
live rows requires a second explicit confirmation and keeps the displayed target
quantity. It never silently adds both quantities or rewrites past purchases.

Finish names exact checked rows and frozen final facts. Concurrent closes buy an
item once; an empty losing close creates no trip. Undo is shown only while its
accepted trip still exists in the current list. Conflicts show current and
requested values. Keeping current dismisses the local command and rebases.

## Membership and local retention

Sharing settings show public device identities and fingerprints. Owners can
rotate keys, remove another device and prepare ownership transfer. Transfer
requires the recipient's separate acceptance file. Expired proposals require
fresh acceptance. Recovery keys can be hidden after saving; they never enter
normal session persistence.

After access removal, unsent commands and conflict details can be saved through
the system file picker. This separate pending-work JSON is a human-readable
record, not a shopping archive or automatic replay file. It contains source
commands and requested/current facts, never encrypted transport bytes, tokens or
private keys. A retained independent local copy includes current shopping facts,
history and local choices without the old command lineage. Removing this device's
shared copy requires confirmation. Removing its last household list forgets
local enrollment first and preserves consumed encryption reservations.

A missing wrapping key fails closed. An explicit local reset is available only
for unavailable private state and preserves Room shopping work. Healthy private
keys and counters cannot be reset through that action. Re-enrollment is required.

## Verification boundary

Docker tests cover authenticated retries, lost acknowledgements, receipt pin
continuity, stale epochs, revocation and source collisions. Room tests cover
transactional confirmation/rebase, pinned UI changes, exact Finish/Undo, explicit
merge guards, retained copies and pending export. Native two-emulator checks use
synthetic lists and a disposable loopback relay. A fresh physical-phone pass,
cluster deployment and the week-long household pilot remain separate gates.
