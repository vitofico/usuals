# Room 4 to 5 and archive 2 design

S1 implements and tests the migration from the committed schema-4 identity
`f5f7a844469a9da081a3f5070e8662b3` to exported schema 5. No destructive fallback
is permitted from version 4 onwards.

## Preserve the installed data

Create one stable local household/list per installation, once in the migration
transaction. Store their generated UUIDs before exposing the list. Every old
item, trip, purchase, preference and alias goes into this default local list,
including checked and tombstoned items. Preserve ids, timestamps, Double values,
nullable fields, source flags and ordering exactly. The `product_quality` table
stays unchanged and global to the device, outside list sync and user archives.
An empty schema-4 database produces one empty local list.

Rebuild scoped tables with composite keys `(listId, id)` for items/trips/purchases
and `(listId, key)` or `(listId, fromKey)` for settings/aliases. Purchase-to-trip
foreign keys include listId; a cross-list trip reference must fail. Keep original
indexes with listId as the leading lookup column. Copy first, compare counts and
facts, then swap tables in the same migration transaction. Export the real Room
schema and test foreign keys and reopen; interruption must leave version 4 or
complete version 5, never partial copies.

Split old `item_pref` into shared category/repeat settings and device-local
mute/snooze records. Preserve both even when one is null/default. Local state
for offered repeat/merge dismissals and provider/catalog settings stays local.
S1 must audit every SharedPreferences key and scope shopping-only dismissals
to a local list without copying credentials. Do not turn a local setting into
shared history merely because it occupied the same old table.

Add household/list/member/device routing, selected-list id, received-envelope
ledger, field/lifecycle revisions, item redirects, effective close/cancellation
references, deterministic repeat occurrence ids and conflict records. Add a
durable intent/outbox table with stable intent id, source command and exact
encrypted envelope once ready. Commit every mutation path and intent together.
Secrets and encrypted keysets belong in `:auth`, not these tables.

Keep shared tombstones and acknowledged envelopes indefinitely in v1. The
existing fortnight `ItemRepository.reap` must apply only to local-only rows;
running it on a shared row would allow a long-offline phone to resurrect it.
All Finish/repeat maintenance locks must include list scope. Imported past shops
have item-derived purchase ids allocated once and retained across retries.

## Sharing and initial snapshot

Sharing is a reviewed copy into a new shared list, with new list scope and the
same item ids within that scope. Keep the source local list until acknowledgement
and let the person explicitly archive/remove it. Do not secretly switch both
copies into shared writers. Historical trip copying is off by default; selected
history includes its archived source rows and immutable facts. Current rows,
confirmed aliases, category overrides and repeat rules are shown in the preview.
Copies have fresh revision/genesis references under the shared list's initial
snapshot, never revisions invented from old timestamps. A failed upload leaves
the original local data and an inspectable pending share.

## Archive format 2

Keep format-1 import. Validate completely, then import into an explicitly chosen
local list using its existing immutable-purchase rules. A shared-list destination
requires preview and new bounded import intents, never a direct Room overwrite.

Format 2 contains a version, named-list manifests, selected list id and scoped shopping records:
items, trips, effective purchases, shared preferences and confirmed aliases.
Include local mute/snooze values only as local user decisions for transfer.
Exclude device/member identity, roster, sessions, outbox, crypto keys, received
envelopes, remote cursors, conflicts containing third-party diagnostics, model
configuration/usage and product-quality cache. A list marked shared in an export
imports as an independent local copy with fresh list scope, not membership.
Preserve item/trip/purchase ids within the copied scope. Independent installations
have distinct generated scopes. A restored list remains separate from the new
phone's local Home and uses its local household. Select the imported list only
when the current destination has no shopping facts or preferences. Repeated
imports preserve scope and do not create duplicate copies. Import cannot select a
server, change an endpoint or restore an identity. UI must disclose plaintext
shopping archives as it already does; this is separate from an encrypted kit.

## Migration and contract fixtures

S1 acceptance fixtures include: empty DB; every nullable field; checked and
tombstoned rows; active and archived quantities; every unit; finite fractional
amounts; category/repeat plus mute/snooze; GTIN aliases; alias chains; 2 lists
with identical item/trip ids; archive 1 backward import; archive 2 local restore;
wrong-list foreign key; failed import rollback; restart during outbox insertion;
Finish plus Undo plus later edit; shared tombstone reaping prevention.

For each fixture compare all source facts, table counts, per-list projections,
foreign keys and schema identity after reopen. Test indexes and query plans for
scoped lookups. S0 checks that this design still targets the real schema 4;
it does not report a migration or two-phone flow as implemented.

## S1 implementation status

Implemented locally and verified through the actual Room migration, exported
schema 5, deterministic reducer and named-list UI. See
[local foundation](local-foundation.md). Tests compare all original facts,
nullable fields and units; check scoped indexes and foreign keys; simulate
migration rollback and process interruption; and exercise archive 1/2 and
confirmed/pending catch-up. No membership or transport authority is implied.
