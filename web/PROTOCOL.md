# Browser client protocol and security boundary

This client is served by the relay at its own origin. It never uploads shopping
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
- Enrollment accepts an existing Android invitation for this exact HTTPS origin.
  The browser creates fresh independent device keys. An owner separately checks
  the full reverse fingerprint before approving. The browser verifies genesis,
  every membership transition, inviter fingerprint and its exact approved join
  assertion before accepting keys. SSO never substitutes for this process.
- The browser is an editor client. Owner actions that rotate all list keys,
  household bootstrap and household recovery stay in Android for this iteration.
  Device identities cannot be exported or restored from files, preventing cloned keys and stale reservation counters.
- A strong user passphrase encrypts the complete local vault, bound to its immutable issuer, subject, account id and exact origin in the encrypted record and associated data, using PBKDF2-SHA256
  (600,000 iterations), fresh 16-byte salt and AES-256-GCM with a 12-byte nonce.
  Only encrypted vault records enter IndexedDB. The passphrase and wrapping key
  remain in memory; locking, logout, page hide and idle timeout discard them.
  Lost passphrase means joining as a fresh device. No server reset can decrypt it.
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
