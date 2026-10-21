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
original. The people using it read this as "the web app is treated like another
device" and "it is still difficult to share a list".

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
  it for everyone.
- **Connecting a phone.** The web app's **Connect a phone** QR connects the
  phone as soon as it is scanned: the 32-byte offer secret is single-use and
  lasts 10 minutes, and the web app shows the connected phone with a
  **Disconnect** action. The browser sign-in flow stays for a phone with no
  signed-in computer nearby.
- **Sessions.** A web session slides to 30 days of inactivity. A phone
  credential slides to 180 days and can be revoked from the web app.

Households, device keys, invites, join requests, key packages, recovery kits,
frozen copies, the browser vault and the WebSocket change stream are removed.

## Consequences

- The privacy promise changes: the operator of a server can read the lists of
  its accounts. Lists that never leave the phone (signed out) stay local.
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
- A projection carries the ids of every intent it has applied, so it grows over
  time. Feed entries carry whole projections; responses are compressed. The
  7 MB snapshot cap stays.
