# Browser client boundary

The browser is a thin window onto the signed-in account. The server holds every
list in plaintext and is the authority for its contents, its members and the order
of changes ([ADR 0017](../docs/decisions/ADR-0017-server-held-lists.md)). The wire
contract, including every route, type and error code below, is
[lists v2](../docs/sync/lists-v2.md). This page records what the browser relies on
and what it deliberately does not do.

## What the browser keeps

No list replica survives the page. Lists, cursors and member names live in
memory and are read again from the feed on every load. There is no vault,
passphrase, device key, outbox or IndexedDB database. Local storage holds only
preferences: language, palette, background style, guided-tour completion and
personal favourites/hidden Replay keys scoped to the account and list. Favourites
include their chosen name, quantity, barcode and store so they can be added again;
they do not replace the server's current rows or sync with the phone. Clearing
storage discards these choices without changing any server-held list or purchase.

Earlier versions kept an encrypted vault in IndexedDB (`usuals-private-vault`) and
two storage keys (`usuals-join-invite` in session storage, `usuals-tutorial-v1` in
local storage). Every load deletes them, best effort; blocked or missing storage
never stops the app from starting.

## Transport

- Same-origin `fetch` only, with the session cookie, `redirect: "error"` and
  `cache: "no-store"`. No credential, secret or token is placed in a URL, log or
  browser storage.
- Mutations send `Content-Type: application/json` and `X-CSRF-Token` from
  `GET /api/me`; the browser adds `Origin` itself.
- Responses are read as a bounded stream: 1 MiB by default, 8 MiB for a single
  list and 160 MiB for a feed page of 20 lists. A larger body stops with `too_large`.
- Errors are `{"code": …}`. The browser recognises `unauthenticated`, `forbidden`,
  `not_found`, `list_exists`, `limit_reached`, `rate_limited`, `too_large`,
  `invalid`, `unavailable`, `upgrade_required` and the `ai_*` codes; any other code
  reads as `invalid`, and a timeout as `unavailable`. Any 401 returns to the
  sign-in screen.

## Lists

- **Feed.** `GET /api/lists/changes?limit=20&wait=0` without a cursor on load (a
  reset), following `hasMore` pages, then `wait=25` with the returned cursor while
  the tab is visible. Entries of a page are applied in `seq` order and an entry
  never replaces a newer one. After the last page of a reset, lists the reset did
  not return are dropped. A hidden tab aborts its waiting request and asks again
  when it becomes visible. Failures back off from 1 s to 60 s; `online` and
  visibility changes retry at once.
- **Edits.** `POST /api/lists/{id}/intents` with `{"intents": [Intent]}`, at most
  100 per request, each with a fresh UUID `id`, `listId` equal to `{id}`,
  `schema: 1` and `normalizer: 1`. The browser writes `capture`, `check`, `edit`,
  `delete`, `list_name`, `finish` and `undo`. Each payload carries the revisions
  the browser last saw, so a stale change becomes a conflict instead of
  overwriting someone else's. The reply's `list` replaces the shown list when its
  `seq` is newer. A `conflict` outcome shows the code recorded in
  `projection.conflicts[intentId]`; `rejected` and `upgrade_required` show their
  own notices. A 404 removes the list from the screen.
- **Finish.** Only picked-up rows whose `key`, `name` and safe integer
  `checkedAt` are present are sent, with facts limited to strings, booleans,
  null and that timestamp. `quantity` and `unit` are included exactly when
  present in the stored row; the reducer rejects a finish that omits either
  stored fact, including an explicit null. Valid quantityless captures may
  omit these fields. The reducer compares facts exactly, and JavaScript cannot
  keep the spelling of other JSON numbers.
- **Lists.** `PUT /api/lists/{uuid}` with `{"projection": {"name": …}}` creates
  one (200 or 201); a 409 `list_exists` retries once with a new id.
  `DELETE /api/lists/{id}` deletes an owned list (204).
  `PUT /api/lists/{id}/archived` with `{"archived": true}` archives a list and
  `false` restores it; the reply's list replaces the shown one. A list with
  `archived` leaves the selector for the Archived area, which offers Restore,
  or Leave when an editor sees `archivedByOwner`.

## Sharing

- `GET /api/people?q=` lists other accounts (the first 20 for an empty query).
- `POST /api/lists/{id}/members` with `{"userId": …}` adds one as an editor.
- `DELETE /api/lists/{id}/members/{userId}` removes an editor (owner) or leaves
  (the signed-in account's own id from `GET /api/me`), 204.

The share dialog offers Remove only to the owner and only on editors, and Leave
only on the signed-in person's row when they are not the owner. The server
enforces the same rules; the browser merely hides what would be refused.

## Connecting a phone

**Connect a phone** draws 32 random bytes in the browser and sends only their
SHA-256 (`POST /api/connect/offers` with `{"secretHash": b64url}`). The QR encodes
`https://<origin>/connect#1.<b64url(offerId16 || secret32)>`, exactly 48 bytes
after the version; the secret is zeroed after drawing. The dialog polls
`GET /api/connect/offers/{id}` every 2 s and shows the phone as connected once a
`ticket` appears; 404 or the offer's `expiresAt` ends it. The QR is generated
locally, without uploads or an external service.

The served `/connect` page reads the fragment into memory, removes it from the
address bar and offers **Open in Usuals** (`usuals://connect#<b64url(link)>`).

A phone with no signed-in computer still uses the browser sign-in flow: it opens
`/?connect=<ticket>`, the browser reads `GET /api/connect/{ticket}` and approves
with `POST /api/connect/{ticket}/approve` after the person compares the 6-digit
code derived from the ticket's `fingerprint`.

`GET /api/connections` lists connected phones and
`POST /api/connections/{id}/revoke` disconnects one.

## Account API boundary

`GET /auth/config` reports enabled local and OIDC methods. Navigation to
`/auth/login` starts OIDC. Local sign-in and one-use activation use a new
short-lived cookie-bound CSRF challenge per attempt. Administrator-created local
accounts have no public signup; their one-use setup secrets are shown once and
never enter URLs. `GET /api/me` supplies the current account and CSRF token;
`POST /auth/logout`, session revocation and admin mutations send the exact Origin
and `X-CSRF-Token`. An opaque session reference uses a secure HttpOnly cookie;
OIDC tokens never enter browser storage or cookies. Administrators may disable
accounts or revoke sessions; those screens do not show or change anyone's lists.

## Trust

Reusing history, Replay, favourites or imported rows sends `reuse` with the capture payload.
The reducer atomically skips an existing unchecked canonical identity rather
than increasing its quantity. Ordinary capture retains its existing merge
behavior. Older servers reject this unknown command instead of silently merging
quantities after a rollback. Replay Undo, clear-list and move-source removal guard every editable
field as well as lifecycle, including fields absent when the action was opened.
The updated browser is packaged with the server that supports this command.

The served origin supplies the code and holds the lists, so whoever operates the
server can read them; the browser adds no protection against its own origin. A
strict content security policy and local assets keep third parties out: no remote
scripts, fonts, analytics or service worker.

## Optional photo model boundary

Photo import is an explicit direct request to an operator-allowlisted provider,
separate from list traffic. Selecting or capturing an image makes no request. A
synthetic image capability check must succeed first; prepared photos are sent
only on the Read action. Keys and images stay in dialog memory and are cleared
when the dialog closes. No account cookies or redirect forwarding are used.
Changing provider fields invalidates the prior connection.

Markdown requires no model. Review confirmation sends `capture` intents for the
selected new rows; rows whose canonical key is already on the list are skipped.
