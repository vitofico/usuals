# Usuals, web access and identity

> Superseded premise: the browser vault, household and device-key decisions below were replaced by server-held account lists in [ADR 0017](decisions/ADR-0017-server-held-lists.md). The name, design and identity decisions stand.

2026-10-18. Vito authorized parallel implementation of a more glamorous name,
icon and Android design, browser access to the shared-list server, user management
and SSO through an existing Authentik installation. Work remains local on
`feat/smart-list`. No publication, cluster changes or deployment is authorized.

## Decisions

- Usuals is the confirmed product name. Keep Android package IDs, persisted
  data, backup formats, `USUALS_*` configuration, protocol domains and
  invitation schemes stable.
- One visual system spans Android and browser: editorial headings, ivory paper,
  aubergine primary actions and restrained champagne detail. Accessible text,
  clear grocery check states and generous touch targets remain the priority.
- Android remains fully usable offline without an account. Authentik owns web
  SSO identities. Standalone local accounts are also supported with controlled
  admin provisioning and salted password hashing. Immutable issuer/subject
  identifies each account, with distinct local subjects and no email-based merge.
  Allowed groups gate SSO sign-in; configured subjects gate SSO administration.
  Local administration starts through an explicit protected operator bootstrap.
  Email or the first public registrant never grants administration.
- Authorization code with PKCE, nonce/state, strict issuer/audience/signature
  validation and opaque HttpOnly same-origin sessions protect browser sign-in.
  Identity tokens stay out of browser storage and logs. CSRF protects mutations.
- Account permission and encrypted household membership remain separate. SSO
  cannot decrypt lists, approve a household member or reset household keys.
  Browser devices enroll through a verified Android invitation, with fresh keys.
  Device/account binding makes account disable/logout/session expiry apply to
  linked browser relay sessions. Existing unmanaged Android devices keep the
  reviewed signed-device protocol; household removal rotates their list keys.
- The first browser client edits existing shared lists and replays all current
  Android operation types. Household creation, owner key rotations and household
  recovery remain in Android. Browser assets and APIs share the relay origin.
- Browser keys, verified pins and exact pending envelopes stay in an encrypted
  local vault. Passphrase loss requires fresh enrollment. No restored device-key
  clone or server-side plaintext shortcut is introduced.
- Android UI sentences still live only in `:app`. The new browser UI has its own
  complete English, Spanish, Italian, French and German resource catalogs. Relay
  APIs return structured codes. Adding a locale requires resources, not a registry.

## Web-first household extension, 2026-10-19

Vito explicitly confirmed that both paths must work: sharing an existing Android
list and creating the first household entirely in the web app. This supersedes
the earlier restriction that household creation and all owner actions start in
Android. It does not grant deployment or publication permission.

- Keep Android's offline list and operator-code setup unchanged. Keep the browser
  invitation path for existing Android households.
- Let an admitted, signed-in web account create a household through a same-origin,
  CSRF-protected endpoint. Bind its freshly generated owner device to that account
  atomically. Bound creation with per-account quotas and durable attempt limits;
  support idempotent retry without issuing an operator bootstrap secret.
- Generate owner/recovery keys and the genesis manifest in the browser. Require
  saving and verifying an encrypted recovery kit compatible with Android before
  creation. The server receives public membership and encrypted list data only.
- Support an initial empty shared list and owner invitations, full-fingerprint
  approval and device removal, using the existing membership/snapshot/key-package
  protocol. Persist exact pending work and encryption reservations before I/O.
  Preserve the stronger native client boundary and require fresh devices rather
  than exporting browser identities.
- Add an append-only server migration for bounded account-created households;
  preserve restore fencing. No Android Room or sync framing version change.
- Update first-run tours and empty-state guidance for both paths in all five
  languages. Verify native/browser interoperability, recovery-kit interoperability,
  interrupted creation/membership, account/CSRF/disabled-user boundaries, and real
  two-client encrypted exchange. Complete an independent security/source review.
- Work on local feat/smart-list, with Docker checks through scripts/dgradle,
  emulator/browser verification and commits. Ask before any cluster deployment.

Independent source review covered atomic account binding, CSRF, quotas, recovery
secrets, membership staging and fingerprints. Closed findings: verified discard
of obsolete membership work, permanent quota-rejection cancellation, recovery
after proof persistence followed by a failed key download, and expired approvals
interrupted before commit. Signed expiry witnesses survive TTL cleanup; checking
membership before and after server-clock confirmation preserves keys if a
concurrent approval committed. Approval/removal retries resolve committed work,
including after invitation expiry. Regression tests cover these cases, ambiguous
commits and exact ciphertext reuse. Runtime acceptance and
final Docker evidence are recorded in the release checks after verification.

## Ordered acceptance gates

| Track | Done when |
| --- | --- |
| Brand | Name/icon/theme applied consistently, contrast/resources/build tests pass, existing data survives emulator upgrade, light/dark/large-text screens inspected |
| Identity | Append-only migration and restore tests pass, valid local OIDC sign-in and denial cases pass, CSRF/admin/device binding are exercised, account changes cannot bypass household authority |
| Browser protocol | Golden framing/crypto/reducer fixtures and JVM interoperability pass; invalid membership, rollback, malformed input and interrupted outbox fail safely; documented unsupported quantity/record-ID states preserve work; independent source review closes findings |
| Browser experience | Actual local relay list exchange works with the production Kotlin household client, responsive keyboard-accessible UI and all locale catalogs pass; account/session/admin screens use real endpoints |
| Deployment preparation | Static web assets included in distribution/image, opt-in Authentik settings and restricted egress documented, local candidate rebuilt from clean commit and verified, no cluster calls |

## Evidence and remaining work

Android branding is local commit `70b371d`. The full Docker gate passed 790 active
Android tests, with zero failures/errors and three opt-in OFF skips, plus debug,
release and instrumentation APK builds and lint. Light/dark, large-text,
long-title, capture/shopping and navigation checks passed on emulator-5554.
Original 28-row database/private settings were restored; the phone was untouched.

The final server/browser gate is
`/private/tmp/usuals-server-web-final-release-gate.log`: 132 JVM tests and 90 Node
tests, zero failures/errors/skips. It includes 40 authoritative Kotlin
normalization cases, 95 reducer cases, all 38 shared scenarios and bidirectional
Tink/WebCrypto envelopes/HPKE. Independent identity, local-account, browser,
packaging and preview lifecycle reviews closed all findings. Runtime packaging
contains all five catalogs, discovered locale inventory, local font/license and
SQL migrations 001 to 003; test fixtures and the test-only parser are absent from distribution.

Real browser QA used an isolated disposable relay and the production Kotlin
household client, with synthetic accounts and lists. Full reverse fingerprint
approval, encrypted add/check/edit in both directions, automatic catch-up,
transient pending retry, wrong passphrase, cancel/reopen, lock and
reload passed. All five locales rendered; desktop and 390px dark layouts passed.
Local provision/one-use secret, account/session listing, session revocation,
reader disable and sign-out passed. The phone-width language control remains
accessible. Evidence and screenshots are under `server/build/browser-qa/`.
The preview databases and credentials were removed and browser viewport restored.

The walkthrough caught and fixed browser Window timer receiver binding,
cancelled unlock ownership and HTTP201 account-creation handling. Regressions
cover each boundary. Local usernames now replace UUID fallback labels.

Browser capability limits deliberately preserve work on extreme quantities,
unsafe integers, ordered numeric record IDs and hexadecimal position strings.
Owner bootstrap, membership rotation and recovery stay in Android. The browser
trusts assets delivered by its origin; an active asset operator can observe
unlocked browser keys. See `web/PROTOCOL.md` for the exact boundary.

Deployment validation covers all four account-source modes and 24 invalid identity
inventories, reference-only OAuth secrets and exact-host TLS egress. A fresh
candidate is prepared only from the final clean commit. The preceding candidate
at source 6780245 remains historical and unchanged. Actual Authentik provider
creation, cluster network/storage/TLS confirmation, off-site recovery, capacity
and the household pilot need separately approved live work. No push or cluster
action is authorized or performed.

Fresh local amd64 profiling passes the 512 MiB / 1 CPU sync smoke test with a
499.3 MiB sampled peak and no OOM. Exact 10,480,504-byte authenticated snapshot,
bounded admission and catch-up pass. Account sources are disabled in this
profile; adequate production headroom and account-enabled/many-household load
remain sizing gates. Local WAL/base restore also passes. Evidence is in
`/private/tmp/usuals-deployment-profile-final.log` and
`/private/tmp/usuals-restore-drill-final.log`.
