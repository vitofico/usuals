# Security model

The phone owns its lists and works offline without an account. There is no
telemetry, analytics or crash reporting. There are no retailer credentials or
payment data.

Signing in to a Usuals server is optional. Signed in, every list of the account
lives on that server, which applies every change through the shared reducer and is
the authority for it ([ADR 0017](decisions/ADR-0017-server-held-lists.md)). The
running server, and so its operator, can read those lists. Stored lists are
encrypted with AES-256-GCM under a key file kept outside the database and bound to
their list id, so a copied database or backup without that key reveals no list
contents; a compromised running server is not covered. Lists on a phone that is
not signed in never leave it. Encryption with a key only the user holds was
considered and rejected for now; the ADR explains why.

An account reaches only the lists it owns or was added to; every other list
answers as if it did not exist. Any member can add another enabled account on the
same server, the owner can remove members or delete the list, and a member can
leave. Choosing people shows the display names and email addresses of the other
enabled accounts on that server. Account administration manages accounts and
sessions and never shows or changes lists.

A browser session slides to 30 days from its last use and a phone connection to
180 days; both can be revoked, and disabling an account revokes all of them. The
browser keeps no list data: it shows what the server returns and trusts the
JavaScript its origin serves. See the [browser boundary](../web/PROTOCOL.md) and
[identity guide](sync/web-identity.md) for session, password and restore details.

The web **Connect a phone** action renders a one-use QR locally. Its 32-byte
secret lasts 10 minutes and connects the phone that scans it to this account at
once, with no code to compare, so the QR must stay private while it is shown.
Android asks the person to confirm the server address before contacting it. A
phone that signs in through its own browser instead shows a six-digit code that
the person compares with the browser before approving.

## Secrets and external data

The model key is stored through Android Keystore-backed encrypted preferences.
It is never logged, displayed back to the user, or included in an export.
It is sent to the configured model endpoint for authentication when required.
Provider diagnostics redact the key, bound response excerpts and avoid dumping
full third-party bodies. A hosted model receives explicitly submitted recipe text or prepared photos.
Selecting a local endpoint is a statement about the user's network, not proof
that a server stores nothing.

Recipe and barcode reads originate on the client. In direct native model mode,
provider requests also originate there. With server AI, the authenticated gateway
receives explicitly submitted recipe text or prepared photos and forwards them
to a configured provider. Account-bound credentials use AES-256-GCM with a wrapping
key outside PostgreSQL. Provider revisions bind consent to the selected destination
and models; stale requests fail before upload. Image checks, durable quotas and
separate concurrency protect the gateway. See [ADR-0014](decisions/ADR-0014-server-connection-and-ai.md)
and the [provider operations guide](providers.md).
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
details (brand, pack size, ingredients, nutrition grades, allergens and traces). When Open
Food Facts answers that a barcode is not food, the same barcode goes to the sister database
that answer names (Open Beauty Facts, Open Products Facts or Open Pet Food Facts) for a name,
brand and pack size, under the same rate-limit permit. Those hosts come from a fixed table in
the app, never from the response, and redirects are not followed. The name
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
keys, the account connection, outbox, feed cursor and conflict diagnostics.
Version-1 imports remain supported. An account list in an archive restores as an
independent local copy with a fresh id and no members. An import cannot
change an endpoint or disclose a stored model key. Imports validate before their
transaction and cannot rewrite existing purchase facts. The resulting export is
a readable file at the location the person chose.

## Server-held lists

The isolated Ktor/PostgreSQL server and the phone and browser clients are
implemented locally ([lists v2](sync/lists-v2.md)). Recipe and catalog reads still
originate on the device or browser. Explicit server-AI submissions are readable by
the gateway and selected model provider; the gateway does not read lists on its
own. Without the list key the server cannot open any stored list and refuses to
start, so the key is escrowed separately from database backups. Restoring a backup rotates an external fence: every
session and phone connection is revoked and every client reads its lists again.

The [deployment package](../deploy/README.md) and [runbook](sync/runbook.md)
are local review material. Private metrics have aggregate labels only; no
analytics SDK or app telemetry is added. Trusted forwarded client addresses
require explicit immediate-ingress configuration and CNI enforcement. Actual
cluster deployment, off-cluster recovery and a pilot with real users need
approval and verification.

Weekly reminders are off by default. Enabling asks for Android notification
permission on API33+. The generic notification contains no item names, list names,
sharing credentials or provider data. Scheduling and consent stay in excluded
device preferences and WorkManager storage, with no network or Play Services.
Disabling cancels pending work and the visible notification. A persisted revision
and due time reject stale/duplicate delivery. Consumption is persisted before
posting: a process crash at that boundary can skip one reminder rather than
repeat it. Delivery time is approximate. Zone changes re-plan on app opening or
when pending work wakes; no background timezone listener is installed.

## Written-list import

Markdown is bounded source text, never rendered HTML or fetched links. Recipe
sections and unchecked entries are parsed locally using the input-language packs.
Photo import accepts bounded raster files and re-encodes oriented pixels to remove
source metadata before an explicit model action. Synthetic image probes never
include the selected photo; rejected probes cannot enable a photo upload. All
output remains untrusted and requires the existing review/add confirmation.

Legacy direct browser model connections use exact operator-approved CSP origins,
empty by default. Those direct keys live only in the import dialog memory, without
the gateway, ambient account credentials, redirect following or persistence. A
failed replacement disconnects the old provider. Native requests and browser
requests are cancellable and response-bounded. Camera streams stop on dismissal,
late permission completion and capture errors. These controls do not guarantee
that a model identifies ingredients or quantities correctly.
