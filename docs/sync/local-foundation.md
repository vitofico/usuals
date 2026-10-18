# Local list foundation

The S1 implementation adds the pure JVM `:sync` reducer and Room schema 5.
`LocalReplica` owns projection reduction, transactional source intents and the
outbox. `ServiceLocator` constructs the reducer, replica, named-list repository
and UI adapters. Capture, recipe confirmation, edits, checks, deletion, rules,
aliases, Finish, Undo and imports use those same local transactions.

Each list has its own item/trip/purchase ids, preferences and aliases. A purchase
cannot refer to a different list's trip. The 4-to-5 migration copies every old
shopping fact into one stable, generated per-installation local scope and splits mute/snooze settings into
local preferences. Catalog cache rows remain global and local. Unreleased
schemas 1 to 3 retain their explicitly destructive upgrade policy.

The list picker creates local lists and remembers selection. A transactional
shopping snapshot groups items and history, so a selection change cannot combine
one list's rows with another list's memory. Legacy prompt dismissals bind once to that generated scope before the directory
is exposed. New prompt dismissals are scoped locally.
Conflict review preserves the current value; a person can keep it or open the
item editor. Dismissals are local records outside shared snapshots and archives.
No relay or account is needed for these paths.

Confirmed catch-up accepts records only after transport verification, requires
contiguous sequence/head metadata, and commits projection, ledger and cursor
together. It then rebases still-pending local intents. Unknown versions stop the
cursor. An HTTP receipt alone never advances it. Transport verification and
membership authority are later S2/S3/S4 work, not implied by this local API.

Repeat occurrences keep permanent redirects. Explicit deletion defers a repeat
until a new deterministic cycle is enabled by the next effective Finish. The
[protocol](protocol-v1.md) defines the lineage and cancellation checks. Undo
provenance is durable across process restarts. Shared tombstones never expire;
local physical row reaping retains occurrence provenance needed by the reducer.

Shopping archive version 2 contains named list manifests and scoped shopping
facts and the selected list id. Independent phones retain separate Home scopes;
an empty destination opens the imported list, while a populated destination
keeps its selection. Version 1 still imports into the chosen local list. A shared export
imports as an independent local copy with a fresh scope; repeating the import
uses that copy. Keys, endpoints, membership, outbox, received ledger, cursors,
conflict diagnostics and catalog cache are excluded. Archives remain plaintext
and use the system file picker.

The executable fixtures run through the actual reducer with duplicated delivery
and serialized restart between operations. Room tests cover original facts,
nullable fields, units, indexes, scoped foreign keys, rollback, process restart,
archive transfer and catch-up/rebase. They complement the required full Docker
and emulator acceptance checks.
