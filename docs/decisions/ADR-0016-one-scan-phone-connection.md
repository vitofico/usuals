# ADR 0016 One-scan phone connection from a signed-in browser

Status: superseded by [ADR 0017](ADR-0017-server-held-lists.md), 2026-10-21. It keeps
the connection offer QR, but the phone now connects as soon as it is scanned,
with no code to compare and no household invite. Accepted earlier on 2026-10-21;
it extended [ADR 0015](ADR-0015-pairing-v2.md).

## Context

A phone connected to an account only by signing in again in its own browser,
in the exact tab the app opened. A person with the server already open on a
computer found that nothing there could approve the phone: on 2026-10-21 a
request made at 10:14 expired unapproved at 10:24, although a sign-in finished
at 10:18. The web also had two actions both called "Connect a phone". The one on
the account page only filled in the server address. The one on Shared lists
was a household invite. Connecting your own phone was therefore two pairings.

## Decision

**Connect a phone** in a signed-in browser creates a connection offer. The offer
holds a random 32-byte secret, and the relay stores only its SHA-256 against the
account. An offer lasts 10 minutes, and an account has at most five unclaimed
offers. When the browser is unlocked and owns a household, it also creates a
pairing v2 invite. The QR is

`https://<origin>/connect#1.<b64url(offerId16 || secret32 [|| inviteId16 || k32 || anchor16])>`

and the app form is `usuals://connect#` followed by the base64url link.

The phone sends the secret with its connection start. The relay binds the
ticket to the offer once, so a second claim fails, and the offer then lives
exactly as long as the ticket. The browser polls the offer and shows the
connection's six-digit code. Its approval uses the existing approve endpoint.
After that approval, the phone sends the normal v2 join request with the invite,
signed by the same device keys that produced the connection code. The still-open
browser approves only the request whose verified MAC names its invite and whose
keys reproduce that code. If the dialog was closed, the request waits on the
household card as before. A phone already connected to the account skips the
connection and joins with the invite alone, so the dialog shows its request with
the join code to compare, like any invite.
The sign-in in the phone's browser remains for a first phone that has no
signed-in computer.

## Consequences

- One scan and one approval connect your own phone to the account and the
  household, with no sign-in on the phone.
- The offer secret reaches the relay. That is acceptable because it only grants
  account access, which the relay controls anyway. The invite key never reaches
  the relay: the pairing v2 anchor and MAC rules are unchanged, so the relay
  still cannot substitute keys.
- Whoever holds the QR during its 10 minutes can ask to connect. The single
  claim and the code shown on both screens bound that, as with an invite. They
  can also ask to join with its invite, but without the compared keys their
  request is never approved on the connection's approval; it needs its own code.
- The household device reuses the connection's keys, so the relay can link that
  phone's membership to the account. It could already infer that from timing.
- Only the account that created an offer can see it, and so its phone. Inviting
  another person stays a separate action, **Invite someone**.
- A locked browser, or one that is only an editor, offers just the account
  connection. Its phone joins the household through an owner's invite.
- Migration 007 adds the `connection_offer` table. Older apps do not recognize
  `/connect#1.` links and keep the address-and-sign-in path.
