# Security model

The phone owns the list and works offline without an account. There is no
telemetry, analytics or crash reporting. Optional browser access uses standalone
accounts or Authentik sessions, separate from encrypted household membership.
There are no retailer credentials or payment data.

Browser account administration cannot grant household keys or decrypt a vault.
The browser trusts JavaScript delivered by its serving origin; an active operator
who changes that code can observe an unlocked browser. The independently
installed Android app has the stronger operator boundary. See the
[browser protocol](../web/PROTOCOL.md) and [identity guide](sync/web-identity.md)
for enrollment, session, password and restore details.

## Secrets and external data

The model key is stored through Android Keystore-backed encrypted preferences.
It is never logged, displayed back to the user, or included in an export.
It is sent to the configured model endpoint for authentication when required.
Provider diagnostics redact the key, bound response excerpts and avoid dumping
full third-party bodies. A hosted model receives the text submitted to it.
Selecting a local endpoint is a statement about the user's network, not proof
that a server stores nothing.

All third-party reads originate on the device. No application server fetches a
recipe, a barcode record or a provider response on behalf of a phone.
Recipe pages are fetched only from explicit HTTP or HTTPS links. No scripts or
linked contexts execute. Reads have size and time limits, and blocked pages
produce a clear failure instead of an access workaround. Untrusted prose is
serialized as one JSON string value in model input only after an explicit
model action. Model output is reviewed before it becomes list rows. The saved
daily ceiling and persisted UTC-day usage constrain probes and recipe calls.
HTTP is enabled for explicitly configured LAN providers; a local label does
not encrypt a connection or attest the endpoint.

Scan lookup is off by default. When enabled in Settings or the scanner, a barcode is sent
directly to Open Food Facts for a proposed product name, category and product
details (brand, pack size, ingredients, nutrition grades, allergens and traces). The name
changes only after confirmation and the sheet credits Open Food Facts under
ODbL. Opt-out gates new requests, including ones waiting for a rate-limit permit.
With lookup off, scanning makes no request and offers manual naming. The list
and purchase history are never sent to Open Food Facts. The product cache is
local, separate from shopping history, and never included in a shopping-data
JSON export. Direct database device transfer can carry the local cache to the
receiving phone. Product details show saved data immediately, including when
lookup is off. Only consented reads refresh it; failures retain saved data with
a visible error and saved date. Responses are size-bounded and mismatched codes
are rejected. The source/contribution link opens the product page in the browser
only after an explicit tap. Usuals performs no product writes.
The home-screen widget reads unchecked rows locally and opens the list; it
does not schedule network work.

Voice prefers Android on-device recognition when available. Otherwise the
selected system speech service may process audio off the phone. Usuals cannot
attest that service; keyboard capture remains an offline fallback. This boundary
is also stated on the app privacy screen.

## Backup and transfer

Cloud backup contains no app data on any supported Android version. The manifest
enables the backup framework for direct device transfer, while both the legacy
full-backup policy and Android 12 cloud policy exclude every storage domain.
Android 12 and later may transfer only `usuals.db` and its WAL/shared-memory
sidecars. Preferences, including encrypted model-key handles and provider
configuration, are excluded.

Explicit shopping-data export/import uses the system file picker. The JSON
version-2 archive includes named lists, scoped rows, trips, purchases, item
preferences and aliases. It excludes quality-cache data, provider settings,
keys, membership, outbox, received ledger, remote cursors and conflict diagnostics.
Version-1 imports remain supported. A shared archive restores an independent
local copy with fresh list scope and no authority. An import cannot
change an endpoint or disclose a stored model key. Imports validate before their
transaction and cannot rewrite existing purchase facts. The resulting export is
a readable file at the location the person chose.

## Shared-list implementation

The isolated Ktor/PostgreSQL relay, Android Keystore keys, verified joining and
shopping transport are implemented locally. The relay stores ciphertext plus
public membership/routing metadata. It cannot read shopping names, quantities
or history. Recipe, catalog and model reads still originate on each phone.
No application server enters any third-party read path.

The [v1 contract](sync/security-v1.md) covers signed membership, per-list keys,
receipts, pins, removal, recovery and its freshness limits. Restored household
ids are permanently frozen before authentication. The offline operator can only
export a recipient-scoped encrypted [recovery copy](sync/recovery-copy.md) after
independent known-owner fingerprint verification. The phone verifies signatures,
AEAD, exact overlapping pins, receipts and EOF before an immutable preview and
atomic local-scope save. Existing work and private state are preserved. Missing
ancestry is warned about; a provable fork fails closed. No backup restores old
authority, resets encryption keys or silently merges newer work.

The [deployment package](../deploy/README.md) and [runbook](sync/runbook.md)
are local review material. Private metrics have aggregate labels only; no
analytics SDK or app telemetry is added. Trusted forwarded client addresses
require explicit immediate-ingress configuration and CNI enforcement. Actual
cluster deployment, off-cluster recovery and a household pilot need approval
and verification. Physical-phone sharing has not been claimed from emulators.

Weekly reminders are off by default. Enabling asks for Android notification
permission on API33+. The generic notification contains no item names, list names,
sharing credentials or provider data. Scheduling and consent stay in excluded
device preferences and WorkManager storage, with no network or Play Services.
Disabling cancels pending work and the visible notification. A persisted revision
and due time reject stale/duplicate delivery. Consumption is persisted before
posting: a process crash at that boundary can skip one reminder rather than
repeat it. Delivery time is approximate. Zone changes re-plan on app opening or
when pending work wakes; no background timezone listener is installed.
