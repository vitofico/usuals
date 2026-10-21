# Local list foundation

The pure JVM `:sync` module holds the shared reducer: the phone and the server apply
the same intents through it, so they agree on every list. `LocalReplica` in
`:data:local` owns the phone's copy. `ServiceLocator` constructs the reducer,
replica, named-list repository, list sync and UI adapters. Capture, recipe
confirmation, edits, checks, deletion, rules, aliases, Finish, Undo and imports all
go through those same local transactions, signed in or not.

Each list has its own item, trip and purchase ids, preferences and aliases. A
purchase cannot refer to a different list's trip. A transactional shopping snapshot
groups items and history, so switching lists never mixes one list's rows with
another list's memory. Catalog cache rows stay global and local.

## Room schemas

Schema 4 is the finalized single-list schema. Schema 5 adds named lists: the 4-to-5
migration copies every shopping fact into one generated local list and splits
mute and snooze settings into local preferences. Schema 6 adds product details to
the catalog cache. Schema 7 replaces household
records with the account model: each list records whether it belongs to the signed-in
account, whether the server has created it, its last feed sequence, role and members.
The 6-to-7 migration keeps every list, a former household list included, as an
ordinary local list with what its screen showed, so signing in uploads it whole.
Schema 8 stores each copy of a list's projection (confirmed, and with pending
intents applied) in parts of at most 200,000 characters, because Android reads a
row through a 2 MB cursor window and a list may reach 7 MB. The 7-to-8 migration
splits the old rows inside SQLite, since a row already past the window cannot be
read out to split it.
Unreleased schemas 1 to 3 keep their destructive upgrade policy. Every later schema
change needs a migration and a migration test.

## Pending changes

A list that belongs to the account keeps the server's last projection (the list
state the server confirmed) and an outbox of intents not yet acknowledged. The
screen shows the confirmed projection with the pending intents applied on top.
When the server answers, acknowledged intents leave the outbox and what is still
pending is applied again on the new confirmed state. Conflict review keeps the
current value; a person can keep it or open the item editor. Dismissals are local
records outside archives. [Lists v2](lists-v2.md) describes the push, pull and
feed rules.

Repeat occurrences keep permanent redirects. Explicit deletion defers a repeat
until the next effective Finish enables a new cycle. Undo provenance survives
process restarts.

## Archives

Shopping archive version 2 contains named list manifests, scoped shopping facts and
the selected list id. Version 1 still imports into the chosen local list. A list
that belongs to an account imports as an independent local copy with a fresh id;
importing it again reuses that copy. Account credentials, outbox, feed cursor,
conflict diagnostics and the catalog cache are excluded. Archives are plaintext
files saved through the system file picker.

The reducer fixtures run through the actual reducer with duplicated delivery and
restarts between operations. Room tests cover original facts, nullable fields,
units, indexes, scoped foreign keys, rollback, process restart, archive transfer,
the schema migrations and the confirmed-plus-pending rebase.
