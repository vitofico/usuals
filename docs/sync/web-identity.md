# Browser accounts, local credentials and Authentik

Browser identity is an optional control plane. Operators can enable standalone
local accounts, Authentik OIDC, or both. Public signup stays closed. Authentik
controls its users, password policy, MFA and application admission. Local users
are provisioned through an explicit operator action or an existing administrator.
The existing Android offline experience and signed household protocol remain
available without accounts.

The backend implements confidential authorization code flow with S256 PKCE.
Discovery, exchange and JWKS retrieval happen server-side. The browser receives
an opaque HttpOnly session cookie, never an IdP access token, refresh token or
client secret. Application sessions last 15 minutes without renewal. Logging
out revokes the local session; it does not sign out other Authentik applications.
Authentik deprovisioning, changed group admission or IdP logout takes effect at
the next sign-in, within the fixed session lifetime. Immediate local revocation
is available to application administrators. Back-channel IdP logout and SCIM are
not implemented.

## Trust boundaries

An account is keyed by immutable `(issuer, subject)`. Local accounts use
`urn:usuals:local` and a random UUID subject, independently of their login name.
Emails, display names and
group names cannot merge identities, grant administrator access or change the
key. Only a verified email claim is returned as contact metadata. Administrators
are an explicit configured subject allowlist for the exact configured OIDC
issuer, or a role granted only by the local operator CLI for standalone accounts.
There is no first-user administrator promotion or role elevation through an
ordinary invite or password reset. Optional OIDC allowed groups must be
a signed array in the ID token, and at least one configured group must match.
Without that optional filter, Authentik's application policy controls admission.

Account sign-in alone never creates a household, obtains its keys, approves a join,
resets encryption or bypasses verified recovery. The relay continues to store
opaque ciphertext. A joining browser device follows the existing invitation,
fingerprint verification, owner approval and signed manifest protocol. It then
links its existing principal with a signed session challenge, an active account
cookie and CSRF proof. Signing and encryption private keys remain in the browser.

Linking is immutable through HTTP. The server locks the principal, verifies a
fresh single-use proof, rejects recovery principals and any existing binding,
records the account association and revokes all earlier device sessions in one
transaction. Session issuance takes the same principal lock. New browser bearer
tokens map to the exact application session that issued them. Every signed API
and socket authorization checks both that mapped session and the matching
request cookie, currently enabled identity provider, expiry and enabled account. Missing, different,
revoked or expired cookies fail closed. A request-scoped coroutine context carries
the cookie across JDBC dispatch without leaking it into another request.

Disabling an account or revoking its sessions stops its bound browser sessions.
It cannot erase previously downloaded contents or revoke an unrelated unbound
Android principal. Household removal remains a separate owner-signed action
that rotates keys. Unmanaged devices still require possession of their private
keys and active household membership. Restoring the database revokes browser
sessions, pending logins, local password hashes and unredeemed setup/reset secrets
as part of the independent restore fence, while
retaining immutable account associations and the existing frozen-household rule.

## HTTP contract

The web client and API share an exact origin. There is no CORS allowance, bearer
token in a URL, arbitrary return URL or client-controlled IdP endpoint. Login
always returns to `/`. Identity responses set `Cache-Control: no-store` and
`Referrer-Policy: no-referrer`. Production cookies use the `__Host-` prefix,
`Secure`, `HttpOnly`, `SameSite=Lax`, path `/` and no Domain attribute. Explicit
local-only HTTP development uses unprefixed cookies. Error bodies contain codes,
not provider diagnostics or user-facing sentences.

| Method and path | Result |
|---|---|
| `GET /auth/config` | `{enabled, oidcEnabled, localEnabled, loginPath?}`; loginPath only for OIDC |
| `GET /auth/login` | Provider redirect and five-minute browser binding cookie |
| `GET /auth/callback` | Consume transaction, validate IdP identity, rotate session cookie and redirect `/` |
| `GET /api/me` | `{user, csrfToken, expiresAt}` or 401 |
| `GET /api/sessions` | `{sessions:[{id, createdAt, expiresAt, current}]}` |
| `POST /auth/logout` | Revoke current session, clear cookie, 204 |
| `POST /api/sessions/{id}/revoke` | Revoke an owned session, 204 |
| `POST /api/devices/link` | Existing signed `Proof`, immutable device link, 204 |
| `GET /api/admin/users?after={uuid}` | `{users, next?}`, pages of at most 100 |
| `POST /api/admin/users/{id}` | `{disabled:boolean}`, return updated user |
| `POST /api/admin/users/{id}/revoke-sessions` | Revoke all target sessions, 204 |
| `GET /auth/local/challenge` | Five-minute HttpOnly browser binding cookie and `{csrfToken, expiresAt}` |
| `POST /auth/local/login` | `{username, password}`, rotate session cookie, 204 |
| `POST /auth/local/activate` | `{username, setupToken, password}`, consume invitation/reset, rotate cookie, 204 |
| `POST /auth/local/password` | `{currentPassword, newPassword}`, revoke all sessions, clear cookie, 204 |
| `POST /api/admin/local-users` | `{username, displayName?}`, return `{user, setupToken, expiresAt}` once |
| `POST /api/admin/users/{id}/reset-local-password` | Invalidate previous password/sessions and return replacement setup secret once |

The user object contains `id`, `issuer`, `subject`, `displayName`, optional
`email`, optional local `username`, `admin`, `disabled`, `createdAt` and
`lastLoginAt`. All dates are Unix
milliseconds. The durable account ID and issuer/subject do not change. Browser
vaults and pending enrollment must bind to origin plus issuer/subject, never email.

All identity mutations require an exact `Origin` header and `X-CSRF-Token` from
`/api/me`. Unauthenticated local login and activation instead require the token
from `/auth/local/challenge` plus its matching cookie. That challenge is one-use
on submission, including a failed password, and must be requested again before
retrying. Local login and activation consume the valid challenge before reading
their bounded bodies, with a three-second body deadline. JSON bodies require
`application/json` and strict bounded decoding.
Browser `/v1/devices/session` also requires those headers when using its cookie.
The device link body is the existing `{challengeId, deviceId, signature}` proof
with purpose `session`. Linking consumes that proof, so minting the next bearer
requires a new challenge. A cookie alone never authenticates `/v1` household data.

## Configuration and provider preparation

Nothing here creates an Authentik provider or touches the cluster. Enable only
after reviewing the provider and network policy for the intended environment.

| Variable | Meaning |
|---|---|
| `USUALS_OIDC_ENABLED` | Explicit `true`; default `false` |
| `USUALS_OIDC_ISSUER` | Exact per-provider HTTPS issuer ending `/` |
| `USUALS_OIDC_CLIENT_ID` | Confidential application client ID |
| `USUALS_OIDC_CLIENT_SECRET_FILE` | Read-only mounted regular file, not a URL or environment secret |
| `USUALS_OIDC_ADMIN_SUBJECTS` | Optional JSON array of immutable exact subject strings |
| `USUALS_OIDC_ALLOWED_GROUPS` | Optional JSON array of allowed signed group names |
| `USUALS_LOCAL_AUTH_ENABLED` | Explicit `true` enables standalone local accounts, independently of OIDC; default `false` |

Use a dedicated confidential OAuth2/OpenID provider with per-provider issuer,
stable subject mode, authorization code flow, S256 PKCE and an RSA signing key
for RS256. Configure the exact callback `https://<app-host>/auth/callback` as a
strict redirect URI. Request scopes are `openid profile email`; configure a
signed `groups` claim if filtering groups. This implementation pins RS256,
requires a unique `kid`, RSA 2048 to 4096 bits with exponent 65537, and ignores
no unsigned role or forwarded identity headers. HTTPS discovery endpoints must
have the exact configured issuer authority, no redirect, userinfo, query or
fragment. All metadata capabilities and token issuer, audience, authorized
party, issue time, expiry, nonce and optional groups are verified.

The HTTP transport bounds response bytes, timeouts and redirects. Login,
password hashing and callback network calls have their own single-worker, fail-fast
admission budget, so an unavailable IdP cannot consume the encrypted sync
dispatcher or request slots. Pending
transactions are capped, hashed state and cookie bindings expire after five
minutes, and consumption commits before exchange. Session cookies rotate on
every sign-in. Only session-token hashes persist. Per-account session and total
user/session limits bound storage. SQL migrations 002 and 003 are append-only; applied
migration checksums and unsupported versions remain enforced.

## Standalone account setup and password handling

After enabling local authentication, the operator explicitly runs
`usuals-relay local-admin USERNAME NEW_PROTECTED_OUTPUT_FILE` in the prepared
runtime environment. It creates an administrator and a one-use 24-hour setup
secret, then writes a new mode-0600 JSON file containing `username`, `setupToken`
and `expiresAt`. It refuses to overwrite any existing path. No password or setup
secret is passed as a command argument, printed or logged. The new account
cannot sign in before activation. `usuals-relay local-reset USERNAME
NEW_PROTECTED_OUTPUT_FILE` issues a replacement for an existing local account
without changing its immutable identity or administrator role.

Administrators can create ordinary local users and reset local passwords from
the web UI. The response exposes the setup secret once so the administrator can
deliberately copy it through a trusted channel. There is no automatic email or
message delivery. Secrets stay out of URLs, browser history and query strings.
Each replacement invalidates the previous setup secret, old password and all
account sessions. Activation consumes the new secret atomically with setting
the password and issuing a fresh session. Disabled accounts remain disabled.
Changing a password requires the current password and signs out all sessions,
including the initiating browser.

Login names are case-insensitive ASCII names of 3 to 64 characters using letters,
digits, dot, underscore and hyphen. Passwords require 15 to 128 Unicode codepoints,
preserve spaces without trimming or truncation, and reject malformed surrogate
sequences and control characters. Version 1 uses the JDK's
`PBKDF2WithHmacSHA256` with 600,000 iterations, a fresh cryptographically random
32-byte salt and a 32-byte derived hash. Only salts, hashes, algorithm version
and hashes of setup secrets persist. Unknown accounts still perform the same
KDF against a dummy credential; failures are generic 401 responses. IP and
normalized-login buckets each admit at most ten password attempts per minute.

The account row stays locked through password verification, replacement and
session issuance. A concurrent reset cannot issue a new session from an old
password after the replacement transaction. Password work never enters the
encrypted sync worker budget. The account password and browser vault passphrase
are separate: a password reset neither decrypts a browser vault nor grants
household recovery authority. Losing the vault passphrase still requires the
existing verified enrollment or recovery path.

A changed restore fence also invalidates every local password. A restored
backup must not revive an earlier password or an earlier administrator's usable
credential. Operators first issue a protected `local-reset` for the intended
local administrator and verify delivery; that administrator can then reset other
local users. Existing IDs, roles, disabled flags and device associations stay
stable. OIDC sign-in still verifies the current external provider. Household
freeze and cryptographic recovery remain independent of account reactivation.

The existing local cluster manifests describe Authentik in namespace
`authentik`, server pod label `app=authentik-server` and public issuer host
`auth.theficos.dedyn.io`. These are static inventory facts, not live validation.
The provider, allowed subjects/groups, app hostname, public TLS path and egress
remain operator configuration. Mount the secret as a regular file, for example
with `subPath`; projected symlink mounts are deliberately rejected. Restart to
pick up client-secret or allowlist changes.

Sources: [Authentik OAuth2 provider and endpoint documentation](https://docs.goauthentik.io/add-secure-apps/providers/oauth2/)
and [OpenID Connect Core validation rules](https://openid.net/specs/openid-connect-core-1_0.html#IDTokenValidation).
Password parameters follow the [OWASP password storage guidance](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
and the password-length baseline in [NIST SP 800-63B](https://pages.nist.gov/800-63-4/sp800-63b.html).

## Web-first household setup

An admitted local or SSO account may explicitly create its first household in the
web app after unlocking its separate browser vault. It generates owner and recovery
keys locally, requires saving and verifying a native-compatible encrypted recovery
kit, and binds its fresh owner device atomically through `POST /api/households`.
This same-origin endpoint requires the session CSRF token, limits attempts to five
per minute and retains a permanent maximum of ten created households per account.
Exact genesis retries are idempotent for their original creating account.

Existing Android lists still use Android setup and an owner-approved invitation.
Web owners can invite, approve and remove devices. Recovery and advanced owner
operations use Android and the saved kit. Neither admission nor administrative
account access grants membership in somebody else's household.
