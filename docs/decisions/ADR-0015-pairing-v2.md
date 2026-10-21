# ADR 0015 Pairing v2: one-scan invites and one sharing flow

Status: accepted, 2026-10-21. Supersedes the "Full device verification" section
of [ADR 0014](ADR-0014-server-connection-and-ai.md).

## Context

Joining a household took two dense QR codes or a 64-character comparison. An
invitation was about 740 characters (QR version 19 to 22), read by CameraX at
640x480, so a module was about two pixels and scans failed. The reverse QR or
full fingerprint existed because the invitation secret travels to the relay: a
relay that knows it could swap the applicant's public keys, and only an
out-of-band check caught that. The server QR (`https://host/connect`) was also
dropped by the Android analyzer. The Android screen showed about fifteen actions
at once, and Settings had a separate server page that told members who were
syncing that they were "not connected".

## Decision

Invites carry a 32-byte link key `k` that never reaches the relay. The relay
stores `SHA256(admit)` and the owner's descriptor; `admit` and `macKey` are HKDF
outputs of `k`. The applicant proves it holds the link with
`HMAC(macKey, assertion)`, and checks the descriptor against a 16-byte anchor in
the link. The full construction is in
[security-v1.md](../sync/security-v1.md#pairing-v2). The link is about 125
characters (QR version 7), and CameraX analysis runs at 1280x720.

The owner device that created the invite approves with one tap. Both phones show
a six-digit code derived from the MAC for an optional spoken check. The server
sign-in shows six digits from the existing connection fingerprint instead of 64
hex characters. Invites last 24 hours and work once. Recovery-kit verification
reads the saved file back and checks it with the in-memory key.

Android renders one stage at a time (start, sign in, protect, ready, joining)
with rare actions under Advanced; Settings shows one "Server and sharing" row.

## Consequences

- The relay can no longer substitute keys or the household undetected, and no
  person has to compare long codes or point a computer camera at a phone.
- Whoever holds a link can request to join for 24 hours; the owner's tap and
  single use bound that. Links should travel through channels the owner trusts.
- Only the inviting device can approve. A second owner phone or a browser owner
  sees the request and is told where to approve it.
- Server migration 006 adds two nullable columns. Old invites without a
  descriptor keep working server-side until they expire; new clients neither
  create nor read them, and old app versions report "update needed" for v2.
- Membership manifests, approval records, list encryption, recovery format and
  sync transport are unchanged.
