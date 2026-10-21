# ADR 0017 Server-held lists shared between accounts

Status: accepted, 2026-10-21. Supersedes [ADR 0013](ADR-0013-shared-list-protocol.md),
[ADR 0015](ADR-0015-pairing-v2.md) and the household part of
[ADR 0016](ADR-0016-one-scan-phone-connection.md).

## Context

After one-scan pairing shipped, a connected phone and the web app still did not
show the same lists. Only household lists synced, and the web app was a separate
end-to-end encrypted device with its own vault, its own 15-minute session and its
own household membership. Sharing a list meant creating a household, inviting a
device, comparing a code and approving it, and the shared list was a copy of the
original. In use, the web app felt like just another device, and sharing a list
was still difficult.

What they want is the model of Spunto: the phone and the web app are two windows
onto one account on one server, and several accounts on that server can share a
list with each other.

## Decision

The server stores every list of a signed-in account in plaintext and is the
authority for it.

- **Lists.** A list has one owner account and any number of editor accounts.
  The server keeps its current projection, the same `Projection` the shared
  reducer already produces, and applies every change through that reducer.
  Clients send intents and receive the resulting list.
- **Sync.** Each account reads one change feed with an `<epoch>.<seq>` cursor.
  A change to a list, to its members, or its deletion moves the list to the end
  of the feed with a new sequence number. Clients wait on the feed for up to 25
  seconds, so a change made on the web reaches the phone within a second or two.
  The epoch is the restore fence, so restoring a backup forces every client to
  read its lists again instead of trusting a cursor from the future.
- **Phone.** The phone keeps a local copy of every list with an outbox of
  pending intents, and works offline. When it signs in, every list it holds is
  uploaded to the account. Signing out sends what is pending and removes the
  account's lists from the phone.
- **Sharing.** A list is shared by picking another account on the same server
  from a searchable list of people. Any member can add people; the owner can
  remove members; a member can leave. Deleting a list is owner only and removes
  it for everyone. The owner can also archive a list for everyone, and restore
  it; an editor can archive it only for themself. An archived list leaves the
  main lists on every device and keeps syncing until it is restored.
- **Connecting a phone.** The web app's **Connect a phone** QR connects the
  phone as soon as it is scanned: the 32-byte offer secret is single-use and
  lasts 10 minutes, and the web app shows the connected phone with a
  **Disconnect** action. The browser sign-in flow stays for a phone with no
  signed-in computer nearby.
- **Encryption at rest.** Every stored projection is encrypted with AES-256-GCM
  under a required 32-byte server key file, bound to its list id. A copy of the
  database or of a backup without that key reveals no list contents. The key can
  be rotated with `list-rewrap`.
- **Sessions.** A web session slides to 30 days of inactivity. A phone
  credential slides to 180 days and can be revoked from the web app.

Households, device keys, invites, join requests, key packages, recovery kits,
frozen copies, the browser vault and the WebSocket change stream are removed.

## Consequences

- The privacy promise changes: the running server, and so its operator, can
  read the lists of its accounts. Encryption at rest protects a stolen database
  or backup, not a compromised running server. Lists that never leave the phone
  (signed out) stay local. Encryption with a key only the user holds was
  considered and rejected for now: the server could no longer merge changes, so
  every client would reconcile them again, every device and browser would need
  the key, and sharing would need a key exchange. A later per-list private mode
  remains possible.
- The server key is a new required secret: it must be provisioned and escrowed
  before deploying, and losing it loses every stored list.
- Migration 008 drops the household tables. Their contents were end-to-end
  encrypted, so the server cannot convert them. Phones upload their plaintext
  copies after upgrading. When two phones held the same household list, the
  first upload owns it and the second becomes a separate copy that the owner
  can share again. Lists that existed only in a browser vault are lost, so the
  deploy is preceded by a database backup.
- Rolling back past migration 008 means restoring that backup.
- Every account on a server can see the display names and email addresses of
  the other enabled accounts when sharing. Servers are run for one family or
  group, so this is the intended directory.
- A projection remembers only the last 1,000 intents applied to it, the resend
  horizon in [lists v2](../sync/lists-v2.md). Keeping every intent with its
  payload grew a list by about 45 KB a week for a 25-item weekly shop, which
  reached the 7 MB cap in under two years and, past 20,000 intents, made the list
  undecodable for phones. A phone that sends changes and misses the answer while
  the list takes 1,000 further changes gets a conflict or a rejection for them
  instead of `no_op`, never a second application. The shape of the projection
  did not change, so a phone on an earlier release still decodes every list, and
  its own reducer, which does not drop intents, only affects its local copy until
  the server answers. The purchase history still grows, about 33 KB a week for
  the same shop. Feed entries carry whole projections; responses are compressed.
  The 7 MB snapshot cap stays.
