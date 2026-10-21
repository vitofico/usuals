# Usuals browser client

Buildless native JavaScript modules served by the Usuals server at its own origin.
The server build packages only `index.html`, `assets/`, `src/` and `locales/`. No
npm install, CDN, third-party script or browser provider token is involved.

The browser is a window onto your account. The server keeps every list you own or
were added to, and the browser shows them as the server holds them: nothing is
stored in the browser, so there is nothing to unlock, back up or lose. Your phone
and this browser show the same lists because they read the same account.

## Lists

Sign in with a local account or the configured OIDC provider and your lists
appear. Pick a list from the menu at the top of the paper, or choose **New list**.

- Type in **What do we need?** to add an item. Tick it when it is in the basket;
  **Finish shopping** moves picked-up items into the list's history, with
  **Undo** while the notice is showing.
- **···** next to an item edits or deletes it. **···** next to the list holds
  **Share**, **Rename list**, **Import list or recipe** and either **Delete list**
  (the owner) or **Leave list** (anyone else).
- Changes from other people and from your phone appear within seconds while the
  tab is visible. A hidden tab stops asking and catches up when you come back.
- Two people changing the same thing at once is fine: the server keeps one
  answer and this browser shows a short notice when its own change lost.

## Sharing

**Share** opens one dialog:

- **People with access** shows the owner and everyone added. The owner can
  **Remove** a person; anyone else can **Leave list** from their own row.
- **Add people** lists the other accounts on this server (the first 20 before you
  type, then matches by name or email). **Add** gives that person the list at
  once, with the same editing rights as you. They see it the next time their
  browser or phone checks in.

There are no invitations, codes or links to send: sharing is between accounts on
the same server. Only the owner can remove people or delete the list.

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

**Account** also holds the password change for local accounts, **AI assistance**,
**Sign out** and the list of active browser sessions. Administrators get
**People** for account management. **AI assistance** shows the server-selected
provider; its optional **Advanced** section contains model details and personal
provider or key controls. See the [connection guide](../docs/server-connection.md)
and [provider guide](../docs/providers.md).

## Guided tour

A three-step tour (welcome, your list, sharing) opens on the first visit to this
browser. **Skip tour** closes it, and **Guided tour** in the navigation reopens
it. Completion is remembered in this browser's local storage, so the tour may
appear again if that storage is cleared.

## Markdown and photo import

**Import list or recipe** reads Markdown locally from text or a UTF-8 file, skips
completed checkboxes and recipe instructions, and reviews the selected rows.
Confirmation adds the new rows together. Items already on the list are left alone
rather than increasing their quantities.

With server AI, **Read photo** performs a synthetic compatibility check and then
submits the prepared photo. Legacy direct browser models still require an
explicit memory-only connection; the operator must allow exact provider origins
and the provider must support browser CORS. No photo is sent by selection,
preview or capture alone. Camera permission is requested only on **Take photo**;
cancellation and failures stop the stream.

## Appearance and languages

Use **Appearance** in the sidebar for Plum, Grove or Tide. The choice is local
to this browser and follows system light or dark mode.

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
