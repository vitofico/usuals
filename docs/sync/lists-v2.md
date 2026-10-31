# Lists v2: server-held lists

The decision is [ADR 0017](../decisions/ADR-0017-server-held-lists.md). The wire
types are in `sync/src/main/kotlin/io/theficos/usuals/sync/ListsApi.kt`; `Intent`
and `Projection` are the shared reducer's types in `Reducer.kt`. JSON uses those
field names. Errors are `{"code":"<code>"}` with the HTTP status listed.

## Authentication

Every route below accepts either:

- a phone: `Authorization: Bearer <native credential>` from the connect flow, or
- a browser: the session cookie. Mutations (anything but `GET`) also need the
  `Origin` and `X-CSRF-Token` headers, as the other `/api` routes do.

Missing, expired or revoked credentials answer 401 `unauthenticated`. The phone
credential slides to 180 days from its last use and the web session to 30 days,
each extended at most once a day.

## Change feed

`GET /api/lists/changes?cursor=<cursor>&limit=<1..200>&wait=<0..25>`

Clients may add `&activity=1` to receive a bounded `activity` array in each
returned list. Without that explicit opt-in, list responses preserve the legacy
field set. Mutation replies always omit `activity`, including when another member
has recently changed the list. Empty activity arrays may be omitted, and clients
must treat a missing array as empty. This keeps strict decoders in older Android
releases compatible with the server.

Activity entries have `id`, `kind`, `count` and `at` fields. The server derives the
stable event ID from the accepted intent ID, sets `at` from its clock, and returns
only events authored by other accounts. `items_added` records an explicit capture
or reuse that creates a new row; quantity merges, automatic repeats and history
imports do not emit it. `shop_finished` records a finish that writes purchases;
Undo retracts its retained event, including within the same batch. `count` is the
number of added rows or accepted purchases. At most 64 events per list are retained,
encrypted under the managed list key with a separate authenticated context. The
wire metadata contains no actor or shopping text.

This is recent activity for optional, quiet client notifications, not a durable
activity log. Clients baseline first reads, resets and newly encountered lists,
consume events even while notification preferences are off, suppress foreground
and archived-list alerts, and avoid replaying old events when alerts are enabled.


Returns `ListChanges`. Every change to a list (an applied intent batch that
changed its projection, a member added or removed) gives the list a new `seq`
from one server-wide counter. Deleting a list, removing a member, or a member
leaving gives that member a `RemovedList` entry with a new `seq`.

- `lists` holds, for each list the caller can access whose `seq` is after the
  cursor, the whole current `SharedList`. `removed` holds the lists the caller
  lost after the cursor. Entries of both are merged in `seq` order and the page
  stops after `limit` entries, or earlier once the lists in it hold 4 MiB of
  stored data (a page always carries at least one entry); `hasMore` says
  another page follows. Apply them in
  `seq` order: a list removed and later re-added appears once in each, and the
  higher `seq` wins.
- `cursor` is `<epoch>.<seq>` and opaque to clients. Store the returned cursor
  after applying the page, in the same local transaction.
- A missing cursor, or one whose epoch is not the server's current epoch, answers
  with `reset: true` and every list the caller can access (paged as above, with
  `removed` empty). After the last page of a reset, a client drops every synced
  list it holds that the reset did not return.
- The epoch is the restore fence: restoring a database backup changes it, so every
  client reads its lists again.
- `wait` > 0 makes the request wait, up to that many seconds, for a change that
  concerns the caller when there is nothing after the cursor yet. It then answers
  as usual, possibly with no entries and the same cursor. An account may hold at
  most 4 waiting requests (429 `rate_limited` beyond that). Waiting requests do
  not take one of the server's request slots and are not cut by its 15 s timeout.

The counter is advanced while holding a lock on one row, so a reader never sees a
higher `seq` committed before a lower one.

## Lists

`PUT /api/lists/{id}` with `CreateList` creates a list owned by the caller with
that projection (validated as a snapshot, at most 7 MB encoded). A new empty list
is `{"projection":{"name":"Groceries"}}`.

- 201 with the `SharedList` when created.
- 200 with the current `SharedList` when the caller is already a member: retries
  are safe and never overwrite the stored projection.
- 409 `list_exists` when the id is taken, or was deleted, and the caller is not a
  member. The client creates the list again under a new id.
- 409 `limit_reached` when the caller already owns 500 lists.

`POST /api/lists/{id}/intents` with `IntentBatch` (1 to 100 intents, body at most
2 MB) applies the intents in order through `Reducer(NORMALIZER_VERSION)` under a
lock on the list, then answers 200 with `IntentBatchResult`: one `IntentResult`
per intent and the list after the batch.

`reuse` has the same validated payload as `capture`, but atomically leaves an
existing unchecked canonical item unchanged. It returns `no_op` without a
redirect or quantity increase when another member already added the item.
History, favourites, review and browser list imports use it; ordinary capture retains its quantity
merge behavior. Older servers reject the unknown kind, so a browser kept open
across rollback cannot silently turn reuse into a quantity merge.

- Every intent must have `listId` equal to `{id}`, else 400 `invalid` and nothing
  is applied.
- Outcomes are `applied`, `merged`, `no_op`, `conflict`, `rejected` and
  `upgrade_required`. Each is final: the client removes the intent from its outbox
  whatever the outcome. Conflicts and rejections are recorded in the projection
  exactly as the reducer records them.
- Resending an intent that was already applied answers `no_op`, so a client
  retries a batch whose response it never received. This holds within the resend
  horizon: the projection's `seen` map keeps the last 1,000 intents applied to the
  list, in the order they were applied, and drops the oldest. An older resend is
  not recognised, but the list's revision checks still stop it from taking effect
  twice: it answers `conflict` or `rejected` and is recorded like any other. (An
  undo or a repeat answers `no_op`, and an import of past purchases `applied`
  without changing anything.) A client also reads `seen` to tell its own applied
  intents from unsent ones, so an intent missing from it counts as not applied.
  The next repeat suggestion after a deleted one likewise needs that deletion to
  be within the horizon; otherwise the rule waits for the next purchase.
- If the projection would exceed 7 MB encoded, nothing is applied: 413 `too_large`.
- The list gets a new `seq` when the batch changed the projection.

`DELETE /api/lists/{id}`: owner only (204). An editor gets 403 `forbidden`. The id
is never reused.

`PUT /api/lists/{id}/archived` with `ArchiveList` (`{"archived":true}` to archive,
`{"archived":false}` to restore) answers 200 with the `SharedList`.

- The owner archives or restores the list for every member.
- An editor archives or restores it for themself only. An editor's restore does
  not undo the owner's archive: the list stays archived for them until the owner
  restores it.
- In a `SharedList`, `archived` says whether the list is archived for the caller,
  by the owner or by the caller, and `archivedByOwner` whether the owner archived
  it for everyone.
- An archived list keeps syncing and can still be edited; clients only move it out
  of their main lists. A change of either flag gives the list a new `seq`, so every
  member's devices see it in the feed; a request that changes nothing does not.
  Being added to a list again clears the editor's own flag.

A caller who is not a member gets 404 `not_found` from every list route, whether
or not the list exists. The one exception is creating a list: `PUT` with an id
that already belongs to a list the caller cannot access answers 409
`list_exists`.

Stored projections are encrypted at rest with AES-256-GCM under a required
32-byte server key file, with additional data `USUALS/LIST/1`, a zero byte and
the list id, so a ciphertext cannot be moved to another list. Clients never see
this layer.

## What a list keeps

The reducer bounds what a projection remembers, so a list in use for years stays
well under the 7 MB cap:

- What one intent left behind lasts as long as that intent is within the resend
  horizon: the intent in `seen`, its outcome in `conflicts` or `rejected`, a
  finish in `closes` and `cancelledCloses`, and the row of each item the intent
  took off the list (bought, deleted, or merged into another). So a finish can be
  undone while it is among the last 1,000 intents; an older undo answers `no_op`
  and the purchases stay. A conflict that old no longer shows for review.
- An item's id outlives its row: a bought item's id is its purchase's id, and a
  deleted or merged item's id stays in `tombstones` or `redirects`. A capture
  resent with that id is rejected instead of adding the item again.
- `purchases` keeps the latest 5,000 purchases, by `boughtAt` and then id: about
  four years of a 25-item weekly shop. Older ones are dropped; `trips` stay. A
  purchase records only `id`, `tripId`, `boughtAt`, `key`, `name`, `quantity`,
  `unit`, `gtin`, `fromSuggestion` and `normalizerVersion`, the facts phones keep.
- Live items, `trips`, `tombstones`, `redirects`, `occurrences`, `repeatHeads`,
  aliases, preferences and rules stay. They grow by a few bytes per trip and per
  item deleted, merged or repeated.

Phones mirror the list, so their history and suggestions cover the same
purchases. A 25-item weekly shop with two deleted items a week levels off at
about 2.8 MB: 0.8 MB of `seen`, 0.4 MB of rows and 1.4 MB of purchases.

The shape of the projection does not change, so the bound ships without a
version step. Phones on the previous release do not read lists v2: the household
routes answer them 426, and their local lists keep everything until they update.
After the update, the first intent applied to a list, on the phone or the
server, drops what lies past the bounds, and the phone removes those rows and
purchases from its database as it does any other removal.

## Sharing

`GET /api/people?q=<text>` returns `People`: up to 20 other enabled accounts on
the server whose name or email contains `q` (case-insensitive), ordered by name.
An empty `q` returns the first 20. `detail` is the email when the account has one.

`POST /api/lists/{id}/members` with `AddMember`: any member adds an enabled
account as `editor` and gets 200 with the `SharedList`. Adding a current member
changes nothing. A list has at most 50 members (409 `limit_reached`); an unknown
or disabled account is 404 `not_found`.

`DELETE /api/lists/{id}/members/{userId}`: the owner removes an editor, or a
member removes themself (leaving). 204. The owner cannot leave their own list
(403 `forbidden`); they delete it instead.

## Connecting a phone

**Connect a phone** on the web creates an offer (`POST /api/connect/offers`) and
shows the QR

`https://<origin>/connect#1.<b64url(offerId16 || secret32)>`

The app form is `usuals://connect#` followed by the base64url link. The phone sends
the secret with its connection start (`POST /v1/connect`). A valid, unclaimed offer
binds the ticket to the offer's account and approves it at once: the phone
completes the connection without a code comparison and the web shows the phone
as connected. The browser sign-in flow (`/?connect=<ticket>`) stays for a phone
with no signed-in computer. Phones are listed and disconnected through
`/api/connections`.

## Removed routes

The household relay is gone. These answer 426 `upgrade_required` so an older app
tells its user to update: `/v1/households*`, `/v1/invites*`, `/v1/join-requests`,
`/v1/lists/*`, `/v1/devices/*`, `/v1/changes`, `/v1/account/households`,
`/api/households` and `/api/devices/link`.

## Clients

**Phone.** Each synced list keeps the last server `SharedList` (confirmed) and an
outbox of pending intents. The screen shows the confirmed projection with the
pending intents applied on top through the same reducer.

1. Push: for each list with pending intents, oldest first, send up to 100. On 200,
   remove those intents from the outbox, store `list` as confirmed when its `seq`
   is not older than the stored one, and reapply what is still pending. On 404 the
   list is gone for this account: drop it.
2. Pull: read the feed from the stored cursor until `hasMore` is false.
3. A list created on the phone is sent with `PUT` before its intents. Signing in
   uploads every local list the same way, with its current projection. On 409
   `list_exists` the list is copied under a new id and uploaded again.
4. Sync runs 2 s after a local change, in a loop with `wait=25` while the app is in
   the foreground, and every 15 minutes in the background while signed in.
   Failures back off and keep the outbox.
5. An archived list (`archived: true`) stays synced but leaves the main list
   picker for an **Archived** section, where it can be restored, except by an
   editor when `archivedByOwner` is set: that editor can only leave it. The
   selected list is always one that is not archived; when none is left, the
   phone starts a fresh empty local list.
6. A 401 shows "Sign in again" and keeps everything local until the person signs
   in or signs out.

**Browser.** No local copy: it reads the feed with a reset on load, waits on it
with `wait=25` while the tab is visible, sends intents and renders the list the
server returns.


### List colors

`list_color` carries `color` (null for Automatic, or `plum`, `sage`, `teal`,
`slate`, `ochre`, `rose`) and `expected` (the reviewed color revision, or null).
Every list member may change it. A stale revision creates a conflict without
replacing the current choice. Its revision is independent of the list name.

The durable metadata is `preferences["usuals:list-appearance"]`, containing only
`color` and `revision`. The namespace cannot be written through a shopping
`preference` intent. It is excluded from shopping preferences when materialized.
Keeping the existing projection envelope lets older clients read and preserve the
JSON without understanding the accent. Servers need this reducer to accept new
color commands. Android caches the color in schema 10 and includes it in list
backup manifests; copying a list keeps its color with fresh revision lineage.


## Reviewed occasion packs

An instance advertises `occasionPacks: true` on `/v1/instance` when it accepts
`pack` intents. Missing capability means unsupported. A `pack` has the same row
facts as `capture`, with optional `packCategory`. Unlike `reuse`, it returns
`no_op` for any alive canonical identity, including checked rows. It never
increases quantities or changes existing row details. On insertion only, an
absent canonical category preference can be initialized atomically; an existing
category or repeat rule is preserved. Repeated intent ids retain normal reducer
idempotence. Unknown kinds are terminal `rejected` results. Deploy support on the
server before clients use this command; clients gate account-list writes on the
capability. Pack definitions remain local and do not enter list projections.
