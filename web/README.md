# Usuals browser client

Buildless native JavaScript modules served by the Usuals server at its own origin.
The server build packages only `index.html`, `assets/`, `src/` and `locales/`. No
npm install, CDN, third-party script or browser provider token is involved.

The browser is a window onto your account. The server keeps every list you own or
were added to, and the browser shows them as the server holds them: nothing is
stored as a browser list replica, so there is nothing to unlock or restore. Your phone
and this browser show the same lists because they read the same account.

Personal occasion packs, favourites and hidden Replay suggestions are saved
separately in this browser, scoped to your account and list. They are device choices, like the
phone's personal shopping preferences, and do not sync with the phone. Clearing
browser storage removes those choices; lists and purchases remain on the server.
Use pack export/import to move templates deliberately between clients.

## Lists

Sign in with a local account or the configured OIDC provider and your lists
appear. The selected list is the page heading. Select its name and chevron to switch lists or create a **New list**.

- Type in **What do we need?** to add an item. It reads input like the phone:
  "2 bananas" adds Bananas ×2, "500g flour" adds 500 g, and "milk and eggs" or
  "milk, eggs" adds two items. Items are grouped by category,
  like the phone; **Details** in an item's options sets its category for
  everyone on the list. Tick an item when it is in the basket;
  **Picked up** expands the items already in your basket.
  **Finish shopping** records all checked rows in the list as purchases,
  including rows hidden by the store filter. Concurrent changes are guarded.
  **Undo**, while the notice is showing, removes that finish's purchases and
  restores its rows without overwriting later changes. Shopping mode keeps
  Finish reachable in the checked section.
- **···** next to an item opens its name and quantity. **Details** holds its
  note and store; **More item actions** holds removal, with a confirmation.
  **List options** holds
  **Share**, **New list**, **Rename list**, **Import list or recipe**, **Archive list** and
  either **Delete list** (the owner) or **Leave list** (anyone else).
- **Archive list** moves a list out of the menu at the top of the paper into
  **Archived** below it, where **Restore** brings it back. The owner archives it
  for everyone who has access; anyone else archives it only for themself. On a
  list the owner archived, anyone else gets **Leave list** in place of
  **Restore**. Archived lists keep receiving changes.
- Changes from other people and from your phone appear within seconds while the
  tab is visible. A hidden tab stops asking and catches up when you come back.
- Two people changing the same thing at once is fine: the server keeps one
  answer and this browser shows a short notice when its own change lost.
- The Replay icon next to **History** opens **Review your usuals**. It reviews
  factual purchases from the last eight shops, with **Add**, **Skip**, **Undo
  last choice**, **Review more** and **Don't suggest again**. Existing items,
  recent purchases and items with repeat rules stay out of the queue. Undo only
  removes the exact item the review added; another person's changes are kept.
- **History** searches actual purchases and adds selected items or a past shop
  again. Existing unchecked items keep their quantities, even when another
  person adds the same item concurrently.
- **List options** also holds **Favourites**, **Start shopping**, **Share list
  as text** and **Remove all items**. Shopping mode increases row height and
  hides capture until **Leave shopping mode**. A store selector filters rows.
- **More item actions** saves a favourite or moves/copies an item to another
  active list. A move removes the original only after a new destination item is
  confirmed and the source is still unchanged. **Set repeat rule** edits the
  shared interval; due repeats are added by the connected phone.

## Occasion packs

Open **List options > Occasion packs** to create a new pack or save the current
list. Checked rows start unselected when saving a list. Rename a pack, add or
edit its items and details, or delete it without changing shopping rows.

**Review and add** lets you select items and change quantities for this use;
the saved template keeps its original quantities. Existing checked and unchecked
items are unavailable, including aliases and items outside the current store
filter. If someone adds an item while review is open, their row and quantity
remain unchanged. Cancel leaves the list alone; failed additions keep the review
open for retry.

Templates stay in this browser for the current account and list. They do not
sync with the phone or other browsers. Export/import pack files to transfer them
explicitly, including to and from Android shopping-data exports. Confirmed list
additions follow normal account synchronization. The browser checks server
support before each use; an older or unreachable server leaves the review open.
See [occasion packs](../docs/occasion-packs.md) for file limits and compatibility.

## Quiet notifications

**Preferences > Notifications** offers optional in-app notices for items added
and shops finished by other people on other lists. The master switch and each
type start off. There are no sounds, permission prompts or background browser
alerts. Preferences stay with this account in this browser. See the
[notification guide](../docs/shared-list-notifications.md) for Android's separate
silent background alerts.

## Sharing

**Share** opens one dialog:

- **People with access** shows the owner and everyone added. The owner can
  **Remove** a person; anyone else can **Leave list** from their own row.
- **Add people** lists the other accounts on this server (the first 20 before you
  type, then matches by name or email). **Add** gives that person the list at
  once, with the same editing rights as you. They see it the next time their
  browser or phone checks in.

Sharing is between accounts on the same server. When email invitations are
enabled, Share also offers email invitation and resend controls; registration
and admission depend on server policy. See the
[email invitation guide](../docs/sync/email-invitations.md). Only the owner can
remove people or delete the list.

## Connect a phone

**Account > Connect a phone** shows a QR code. Scan it with the phone's camera
(the server's `/connect` page offers **Open in Usuals**) or from inside Usuals.
The phone connects to your account straight away, with no code to compare, and
the dialog says **Phone connected**. Because scanning is enough, keep the code to
yourself; it works once and expires after a few minutes, and **Create a new code**
replaces it. The QR is drawn on your device without any upload.

**Connected phones** on the same page lists every phone signed in to your account.
**Disconnect** signs one out; its lists stay on the server.

A phone can also sign in without a computer at hand: it opens this site with
`?connect=`, and you approve it here after checking that both show the same
6-digit code.

## Account and AI

**Account** shows **Connect a phone** first. Named sections below it hold
**AI assistance**, **Password and sign out**, and **Active sessions**. Password
changes are available for local accounts. Administrators get
**People** for account management. **AI assistance** shows the server-selected
provider; its optional **Advanced** section contains model details and personal
provider or key controls. See the [connection guide](../docs/server-connection.md)
and [provider guide](../docs/providers.md).

## Guided tour

A six-step tour (welcome, your list, Replay, import, shopping and sharing) opens on the first visit to this
browser. **Skip tour** closes it, and **Preferences > Guided tour** reopens
it. Completion is remembered in this browser's local storage, so the tour may
appear again if that storage is cleared.

## Markdown and photo import

**Import list or recipe** reads Markdown locally from text or a UTF-8 file, skips
completed checkboxes and recipe instructions, and reviews the selected rows.
Photo review keeps the original visible beside editable names and quantities
and offers the full prepared image for closer inspection. Confirmation adds
the new rows together. Items already on the list are left alone
rather than increasing their quantities.

With server AI, **Read photo** performs a synthetic compatibility check and then
submits the prepared photo. Legacy direct browser models still require an
explicit memory-only connection; the operator must allow exact provider origins
and the provider must support browser CORS. No photo is sent by selection,
preview or capture alone. Camera permission is requested only on **Use camera**;
cancellation and failures stop the stream.

## Appearance and languages

Use **Preferences > Appearance** for Plum, Grove or Tide. The choice is local
to this browser and follows system light or dark mode.
Choose **Background style > Plain** or **Artwork** separately. Artwork is the
default, with a faint collage along the page edge; list rows and inputs stay on
opaque paper. Plain keeps quiet page surfaces. Both choices survive reopening
the browser and changing palettes; logos and welcome illustrations remain.

English is the default. Copy `locales/en.json` to another `locales/xx.json` and
translate every value, keeping `{placeholders}` unchanged. Catalog tests discover
all files and enforce complete parity. The language picker discovers the packaged
catalog inventory supplied by the server; labels belong in each catalog.

## Older browsers of this app

Earlier versions kept an encrypted copy of the lists in the browser. On load the
app deletes that old local database and its keys once, best effort; nothing in it
is needed any more because the lists live on the server.

## Tests

From the repository root:

```sh
node --test web/test/*.test.mjs
```

The tests use Node's built-in runner with fake transports: the change feed and
its pause on hidden tabs, intents and their payloads, sharing permissions, the
connect QR golden vector, legacy cleanup, locale parity and a syntax check of
every module. See [the browser boundary](PROTOCOL.md) for what the browser relies
on from the server.
