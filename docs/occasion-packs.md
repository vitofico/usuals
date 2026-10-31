# Occasion packs

## UX and ownership contract

Occasion packs are personal, offline templates alongside favourites. Android stores
and exports them in the existing portable shopping preferences; the browser keeps
them in its account-and-list-scoped local preferences. Android separates signed-out
packs and each canonical server origin/account namespace, retaining other accounts
on disk without showing or exporting their packs. They do not sync between
clients. The UI states this boundary. No launch prompt or automatic addition is
introduced, and repeat rules and suggestions remain unchanged.

List tools opens Occasion packs. The collection offers New pack and Save current
list. Saving a list copies its rows for review, with checked rows initially
unselected. New pack starts with an empty editable item list. A named pack can be
renamed, have individual rows added, edited or removed, or be deleted. Saving or
deleting templates never changes shopping rows. Names are required; item names,
quantities, units, notes, category and store tags are preserved explicitly.

Use opens an explicit review. Existing current-list identities, including aliases and
rows outside the store filter, are labelled Already on list and cannot increase
an existing quantity. Remaining rows start selected; each quantity can be edited
before Add selected. Cancel leaves the list unchanged. Closing or restarting an
unfinished review discards only its transient choices; the saved pack remains.
Confirmation checks the full current list again, including checked and store-filtered rows. A shared-list addition accepted
while review is open wins and the pack row is skipped. Retry uses stable item
identities and the reducer's `pack` conditional-add contract, so no duplicate or silent
quantity increase occurs. Failed adds retain review for retry.

## Persistence and compatibility

Extend the bounded, validated portable preference model with default-empty packs
and default-null extra item details. Old archives still import; new exports keep
only the current namespace's packs and validate their bounds and identities before importing any facts. The personal preference version moves from 1 to 2 with defaults preserving
existing favourites and layouts. No Room migration or dependency is required. Browser storage remains explicitly local. Its pack collection exports and imports
`{"formatVersion":1,"occasionPacks":[...]}` using the same item schema as Android.
Android data import also accepts this pack-only file, and the browser accepts
`personalShopping.packs` from Android data archives. Import merges identical ids
idempotently and rejects conflicting ids before changing data. Browser imports
are limited to 10 MiB; Android full archives retain their existing size limit.
Each pack has at most 200 rows; each namespace has at most 100 packs and 2,000
rows. Android applies those totals across retained account namespaces too.

## Wire and older servers

`GET /v1/instance` advertises `occasionPacks: true`; absent means unsupported.
The new `pack` intent uses capture facts plus optional `packCategory`. It skips
any alive row with the canonical identity, checked or unchecked. Only a newly
inserted row can initialize a missing category preference, in the same reducer
operation. Existing explicit categories and repeat settings remain authoritative.
Ordinary capture and favourite/history reuse retain their existing behavior.

Update the server before using packs on shared lists. Android checks capability
before queuing a pack for a server list, refreshing legacy connection metadata.
Verified support is cached for offline use. Browser checks before each use. A
missing capability or connection failure retains the open review. Local Android
lists never need a server. Unknown `pack` commands are terminal rejections on
older servers; existing sync acknowledges the answered batch and exposes the
rejection as a conflict, so a server downgrade cannot block unrelated edits or
retry forever. The saved template remains available to reopen. As with any
interrupted review, transient selection/quantity edits are not durable.

## Implementation and polish

`ShoppingToolsStore` persists validated templates; `OccasionPacksSheet` and the
browser collection provide creation, editing, review and deletion. Templates
preserve explicit quantities, units, notes, category overrides, stores and GTINs.
Renaming an item clears its old GTIN to avoid retaining a different identity.
No units are guessed or converted.

Receipt correction on Android offers each nonblank parsed line with the original
photo in its correction dialog and the existing full-image zoom viewer. Browser
photo review keeps the original photo visible, offers the full prepared image
and editable name/quantity fields per line. No bounding boxes or crops are
invented. Android shopping mode keeps one Finish action at the bottom when displayed checked rows
exist, omitting the inline list action in that mode. Normal mode keeps its inline
Finish action. It uses the same displayed-row/version snapshot as the other Finish
buttons; checked rows outside the current store filter and concurrently edited
rows are left untouched. Browser Finish stays sticky within its checked section. Both explain
recorded purchases and Undo without adding a confirmation step.

## Polish and accessibility

Receipt correction shows the original image with the parsed lines and offers the
existing full-image zoom viewer. No crop or bounding box is inferred. Shopping
controls retain 48 dp/px targets, wrap at large text, and keep Finish reachable.
Finish copy explains that checked items become recorded purchases and that Undo
is available. No confirmation step is added. English, Spanish, Italian, French
and German receive complete copy. Dialogs have titles, labelled fields, focus
restoration and scrolling bodies with reachable actions.

## Verification

Cover preference validation and round-trip/import compatibility, duplicate and
concurrent adds, quantities and details, browser review behavior, locale parity,
and receipt rendering. Central Android/server gates and emulator acceptance are
run by the coordinating agent. Acceptance includes empty creation, save-current,
edit/delete without list mutation, cancel/retry review, shared-list race, offline
restart/export/import, image inspection, and large-text shopping on both clients.


## Acceptance walkthrough

1. Open Favourites from Android list options, then Occasion packs; in the browser
   use List options, Occasion packs. Create an empty Visitors pack and add an item
   with a quantity, unit, note, category and store. Save, reopen and edit it.
2. Save a current list containing checked and unchecked rows. Verify checked rows
   start unselected, adjust the template name and selection, then save. Editing or
   deleting a pack must leave all current shopping rows untouched.
3. Review a pack with one identity already present, including a checked item or
   an item hidden by the store filter. It is labelled and unavailable. Change a
   remaining quantity and add. The saved quantity must remain unchanged.
4. Add that remaining identity from another shared client before confirming.
   Confirm, retry and sync. One row must remain with the other person's quantity,
   note, store and explicit category. Repeat with a checked row.
5. Close a review, restart offline and reopen its pack. The saved template remains;
   transient choices reset. Export/import across Android and browser, retry the
   same file and reject a conflicting id without changing current list facts.
6. Switch accounts, servers and signed-out mode. Each sees only its own packs;
   returning restores them, and export includes only the current namespace.
7. With an older server, review/add must show a recoverable error before queuing
   list changes. With a supported server previously verified, Android offline use
   queues normally and converges once reconnected.
8. Correct a receipt line while comparing the original, zoom it, save and verify
   only the chosen line changed. In shopping mode and at large text, check items,
   reach Finish, verify recorded purchases and use Undo.

Local verification on 2026-10-31 passed the Android build, JVM tests, lint and
native-library alignment gate; the disposable PostgreSQL/server/browser gate;
and the secret scan. All 133 browser tests passed, including model validation,
archive interchange, account/list isolation, explicit review, per-use quantities,
failed-add retry and checked duplicates.

The two native scenarios below passed in normal and large-text variants: four
actual test executions, zero failures and zero skips. Production editor and item
dialog checks at 320 dp and 1.8 font scale confirmed readable labels, reachable
actions, checked-row defaults and duplicate handling. Verification used synthetic
data, disposable local PostgreSQL and an Android emulator.


The opt-in native regression
`ShoppingFinishDeviceTest.footerPreservesConcurrentEditsAndFilteredRowsWithUndo`
uses real Room data and a frozen `ListContent` presentation. It clicks the actual
footer after quantity/check edits, verifies filtered rows remain, and tests Undo.
Run the debug instrumentation APK with `shoppingFinishQa=synthetic`, once normally
and once with `shoppingFinishLarge=true` (1.8 font scale, 320 dp content width).

`OccasionPacksDeviceTest.currentListEditingReviewedUseAndDurableRestart` is an
isolated real-Room/SharedPreferences sheet regression. It checks current-list
selection defaults, template edits without list mutation, persisted controller
recreation, checked/unchecked duplicates across stores, cancel, per-use quantities
and successful reducer-backed addition. Run with `occasionPacksQa=synthetic`, then
repeat with `occasionPacksLarge=true`. Both runs use a 320 dp logical viewport;
the large variant uses 1.8 font scale. Its preference file and database are
synthetic and cleaned after the test.
