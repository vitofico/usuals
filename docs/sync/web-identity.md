# Browser accounts, local credentials and OIDC

Browser identity is an optional control plane. Operators can enable standalone
local accounts, OIDC (including Authentik and Keycloak), or both. Public signup
stays closed by default. The identity provider controls its users, password
policy, MFA and application admission. Local users
are provisioned through an explicit operator action or an existing administrator.
Android works offline without an account. An account is what lets a phone or a
browser keep lists on the server and share them with other accounts there.

The backend implements confidential authorization code flow with S256 PKCE.
Discovery, exchange and JWKS retrieval happen server-side. The browser receives
an opaque HttpOnly session cookie, never an IdP access token, refresh token or
client secret. A browser session slides to 30 days from its last use and a phone
connection to 180 days, each renewed at most once a day. Logging out revokes the
local session; it does not sign out other Authentik applications. Authentik
deprovisioning, changed group admission or IdP logout takes effect at the next
sign-in, which can be weeks away, so when removing someone also disable their
Usuals account: that signs out every browser and phone at once. Back-channel IdP
logout and SCIM are not implemented.

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

An account sees the lists it owns or was added to, and nothing else. A list is
shared only when one of its members adds another account
([lists v2](lists-v2.md)). Administrators manage accounts and sessions; those
screens never show or change anyone's lists. Every list request is checked
against an unrevoked, unexpired session or phone connection of an enabled account
from a currently enabled identity provider. Missing, revoked or expired
credentials fail closed.

Disabling an account or revoking its sessions signs out its browsers and phones.
It cannot erase lists a phone already holds offline. Restoring the database
revokes browser sessions, phone connections, pending logins, local password hashes
and unredeemed setup/reset secrets as part of the independent restore fence, while
keeping account ids and list memberships.

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
milliseconds. The durable account ID and issuer/subject do not change. Lists and
their members bind to the account ID, never to an email.

All identity mutations require an exact `Origin` header and `X-CSRF-Token` from
`/api/me`. Unauthenticated local login and activation instead require the token
from `/auth/local/challenge` plus its matching cookie. That challenge is one-use
on submission, including a failed password, and must be requested again before
retrying. Local login and activation consume the valid challenge before reading
their bounded bodies, with a three-second body deadline. JSON bodies require
`application/json` and strict bounded decoding. List mutations from a browser
need the same `Origin` and `X-CSRF-Token`; a phone sends its bearer credential
instead.

## Configuration and provider preparation

Nothing here creates an Authentik provider or touches the cluster. Enable only
after reviewing the provider and network policy for the intended environment.

| Variable | Meaning |
|---|---|
| `USUALS_OIDC_ENABLED` | Explicit `true`; default `false` |
| `USUALS_OIDC_ISSUER` | Exact per-provider HTTPS issuer, with or without a trailing `/` |
| `USUALS_OIDC_CLIENT_ID` | Confidential application client ID |
| `USUALS_OIDC_CLIENT_SECRET_FILE` | Read-only mounted regular file, not a URL or environment secret |
| `USUALS_OIDC_ADMIN_SUBJECTS` | Optional JSON array of immutable exact subject strings |
| `USUALS_OIDC_ALLOWED_GROUPS` | Optional JSON array of allowed signed group names |
| `USUALS_LOCAL_AUTH_ENABLED` | Explicit `true` enables standalone local accounts, independently of OIDC; default `false` |

Copy the issuer exactly from the provider's discovery document. For example,
an Authentik issuer can be `https://auth.example.org/application/o/usuals/`,
while a Keycloak realm can use `https://auth.example.org/realms/usuals`.
Only discovery URL construction removes the trailing slash before appending
`/.well-known/openid-configuration`, following
[OpenID Connect Discovery section 4.1](https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderConfigurationRequest).
Discovery metadata and signed tokens must still match the configured issuer
exactly, including that slash. Changing an existing issuer changes the account
namespace; do not add or remove a slash to migrate existing accounts.

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
admission budget, so an unavailable IdP cannot consume the list request slots.
Pending
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
list request budget. Changing or resetting a password signs out the account's
browsers and phones.

A changed restore fence also invalidates every local password. A restored
backup must not revive an earlier password or an earlier administrator's usable
credential. Operators first issue a protected `local-reset` for the intended
local administrator and verify delivery; that administrator can then reset other
local users. Existing IDs, roles, disabled flags and list memberships stay
stable. OIDC sign-in still verifies the current external provider.

A typical Authentik install runs in namespace `authentik` with server pod label
`app=authentik-server`; the examples use the issuer host `auth.example.org`.
Confirm both against your own cluster.
The provider, allowed subjects/groups, app hostname, public TLS path and egress
remain operator configuration. Mount the secret as a regular file, for example
with `subPath`; projected symlink mounts are deliberately rejected. Restart to
pick up client-secret or allowlist changes.

Sources: [Authentik OAuth2 provider and endpoint documentation](https://docs.goauthentik.io/add-secure-apps/providers/oauth2/)
and [OpenID Connect Core validation rules](https://openid.net/specs/openid-connect-core-1_0.html#IDTokenValidation).
Password parameters follow the [OWASP password storage guidance](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
and the password-length baseline in [NIST SP 800-63B](https://pages.nist.gov/800-63-4/sp800-63b.html).
