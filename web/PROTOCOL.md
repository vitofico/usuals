# Browser client protocol and security boundary

This client is served by the relay at its own origin. The relay never receives shopping
plaintext or private household keys. SSO controls account/session access only;
existing signed household membership remains an independent authority.

## Implementation boundary

- Buildless native modules and WebCrypto, no remote scripts, fonts, analytics or
  service worker. All UI copy is in complete locale JSON files, English default.
- The browser trusts code delivered by its serving origin. An active operator who replaces JavaScript can read an unlocked browser, so Android retains a stronger boundary against the relay operator. Strict CSP and local assets reduce third-party exposure, without claiming protection from that origin.
- Secure context required. Signing uses Ed25519, recipient agreement X25519,
  HKDF-SHA256 and AES-256-GCM. HPKE follows RFC 9180 base mode and the exact
  existing Tink RAW profile. Byte-level golden vectors and JVM interoperability
  are mandatory before a browser-created ciphertext is accepted as verified.
- Enrollment accepts an existing Android or browser-owner invitation for this exact HTTPS origin.
  The browser creates fresh independent device keys. An owner separately checks
  the full reverse fingerprint before approving. The browser verifies genesis,
  every membership transition, inviter fingerprint and its exact approved join
  assertion before accepting keys. SSO never substitutes for this process.
- A web owner can display the existing one-use invitation as a locally generated
  QR. Scanning it on Android fills the invitation, then requires explicit server
  confirmation before network access. Reverse QR approval decodes the exact
  native `PendingJoin`, verifies its signed assertion and invitation ID, and uses
  the existing membership transition and lost-acknowledgement recovery. There is
  no new bearer token, enrollment endpoint or framing version. A QR from another
  invitation, household, server or device cannot authorize the displayed request.
  Browser camera access is same-origin, video-only and requested on a button
  press. Frames are bounded to 800 pixels per side, decoded locally and cleared
  on close, expiry, lock, page hiding, failure or successful capture. Microphone,
  geolocation and payment permissions remain disabled. Manual full-fingerprint
  approval remains available without camera permission.
- An admitted browser account can create a household through CSRF-protected
  `POST /api/households`. The relay atomically stores genesis, the creating-account
  association and the fresh owner-device binding. The account row serializes the
  permanent ten-household quota and disabling; durable attempts are limited to
  five per minute. Exact genesis retries are idempotent for the same account.
  Android operator-token creation remains unchanged. No operator token enters
  the browser.
- Browser creation requires a downloaded and read-back-verified native-compatible
  encrypted recovery kit, with a separate random key. Only owner keys and public
  recovery membership enter the persisted browser vault. Browser owners can
  invite, approve full fingerprints and remove devices, rotating every list key.
  Exact snapshots, manifests and reservations persist before network I/O. A
  verified obsolete change can be discarded; ambiguous committed work is resolved
  first. Quota-denied creation can be cancelled only after a definitive rejection,
  and retry revokes that permission before I/O.
- A pending approval retains the applicant's signed assertion as an expiry witness.
  Owner-authenticated `POST /v1/households/{h}/join-requests/{r}/expired` verifies
  its signature, routing, genesis and request ID under the household lock and
  returns its `requestId`/`expiresAt` only once the server clock reaches expiry.
  It works after TTL cleanup without changing membership. The browser binds the
  witness hash to its staged approval and checks for an exact committed manifest
  both before and after this check before discarding uncommitted keys. Uncertain
  responses preserve all pending state.
- Recovery, recovery replacement and ownership transfer remain native actions.
  Device identities cannot be exported or restored from files, preventing cloned
  keys and stale reservation counters.
- A strong user passphrase encrypts the complete local vault, bound to its immutable issuer, subject, account id and exact origin in the encrypted record and associated data, using PBKDF2-SHA256
  (600,000 iterations), fresh 16-byte salt and AES-256-GCM with a 12-byte nonce.
  Only encrypted vault records enter IndexedDB. The passphrase and wrapping key
  remain in memory; locking, logout, page hide and idle timeout discard them.
  Lost passphrase means joining as a fresh device, or recovering a browser-owned
  household in Android with its saved kit. No server reset can decrypt the vault.
- Web Locks serializes all vault/network mutations across tabs. A tab without
  Web Locks cannot unlock. IndexedDB atomically stores each complete encrypted
  revision. Save a durable per-device/list/epoch/key reservation before every
  encryption attempt; retain the existing 4,096-use ceiling. Persist source
  intents and exact encrypted envelopes before network access. Retry exact bytes.
- Keep confirmed projection/cursor, pending intent/envelope, current/previous
  membership proofs, list keys, highest verified membership and head pins plus
  authenticated append receipts together in the encrypted vault. Unknown versions,
  unsafe integer ranges, malformed JSON, duplicate keys, rollback/fork, revoked
  membership and restore fences stop synchronization without dropping work.
- Catch up through every authenticated envelope using the full deterministic
  reducer. UI writes only capture, check, edit and delete. Other supported
  Android commands still reduce so Finish, repeats, aliases and imports remain
  compatible. Revisions govern conflicts; time never chooses a winner.
- Verify signature, header routing, contiguous receipt/head and current author
  membership before decrypting an operation. Snapshots require exact current or
  preceding authorized signer and committed cutoff hash, plus a receipt bridge
  from every retained known head. Persist append receipt witnesses before reads.
- Same-origin fetch only, redirect errors, bounded streaming responses, no raw
  diagnostics, no credentials in URLs or console. Fragment invitation secrets
  are consumed and removed before rendering. JSON shapes reject duplicate keys,
  oversized input, excessive depth/nodes and the reserved internal numeric marker. Prototype-like user keys use safe own-property dictionaries.

## Account API boundary

`GET /auth/config` reports enabled local/OIDC methods. Navigation to
`/auth/login` starts OIDC. Local sign-in and one-use activation use a new
short-lived cookie-bound CSRF challenge per attempt. Local account passwords
never derive browser-vault keys. Administrator-created local accounts have no
public signup; their one-use setup secrets are shown once and never enter URLs. `GET /api/me` supplies the current account and CSRF token;
`POST /auth/logout`, session revocation and admin mutations send exact Origin and
`X-CSRF-Token`. An opaque session reference uses a secure HttpOnly cookie; OIDC tokens never enter browser storage or cookies. Administrators
may disable accounts or revoke account sessions; they cannot grant household
membership or decrypt contents. Administrative screens make that scope explicit.

## Verification gates

Wire/header/hash and Ed25519 vectors, HPKE browser/JVM round trips, all shared
reducer scenarios, malformed input/adversarial membership and snapshot/receipt
regressions, durable outbox interruption/reload, browser vault wrong-passphrase
and lock tests, local real-relay interoperability and responsive browser QA.
No cluster deployment or production-browser compatibility claim follows from
local checks. Chromium and Firefox/modern Safari need native Ed25519/X25519 and
Web Locks support; unsupported browsers fail closed with translated feedback.

## Browser numeric capability

The browser retains the original spelling of numeric JSON tokens, including
`0`, `0.0` and exponents, through reducer copies and encrypted vault reloads.
This preserves JVM `JsonPrimitive` identity for revisions, facts and source
intent acknowledgements. Its reserved internal marker cannot be supplied by an
incoming JSON object; serialization emits the original numeric token instead.

Quantities are bounded to zero or the inclusive range 0.000001 to
1,000,000,000,000. Extreme or unsupported Java decimal representations stop
catch-up with upgrade-required before consuming the operation or changing its
projection. Pending work, cursors and pins remain intact. All locales explain
that the quantity must be reviewed in Android. Positions and timestamps do not
use this quantity bound. This is a browser capability limit, not a changed
household protocol or Android model.

The browser also stops before consuming snapshots or operations that introduce
JavaScript array-index keys in ordered row, close or intent-ledger maps. These
legacy identifiers could change insertion order and therefore change merge or
repeat semantics. Normal Android UUID identifiers are unaffected. Numeric item
names in preferences and rules remain supported. All locales direct the person
to review or copy a legacy list through Android; pending browser work remains.

Quoted hexadecimal floating-point position values, accepted by Java’s number
parser, also stop before consumption with a localized number-format capability
message. Canonical quantity validation still rejects hexadecimal quantity text.
This avoids a second numeric parser with different rounding behavior.

## Optional photo model boundary

Photo import is an explicit direct request to an operator-allowlisted provider,
separate from household transport. Selecting or capturing an image makes no
request. A synthetic image capability check must succeed first; prepared photos
are sent only on the Read action. Keys and images stay in dialog memory and are
cleared on dismissal or vault lock. No account cookies or redirect forwarding
are used. Changing provider fields invalidates the prior connection.

Markdown requires no model. Review confirmation commits selected new capture
intents atomically, with existing canonical identities excluded. This introduces
no wire operation, schema, normalizer, framing or encryption change.
