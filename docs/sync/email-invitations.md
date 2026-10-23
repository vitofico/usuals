# Email registration and list invitations

Email onboarding is an optional part of the ordinary open-source server and web
app. OIDC continues to own verification, credentials and recovery. Usuals owns
its account sessions, admission policy and list invitations. There is no second
login service or hosted fork. Local operator-created accounts continue to work
when email is disabled.

## Try the full flow locally

With Docker and Node 22.16+ available:

```sh
node server/scripts/email-preview.mjs
```

This builds through `scripts/dgradle` and starts the standard server with real
Keycloak 26.8.1, Mailpit and a disposable PostgreSQL database. Open
`http://localhost:8090/` and `http://localhost:8092/`. Use an address such as
`alice@example.test`. All verification and invitation mail is captured locally;
no email is sent outside the Docker network. The identity login uses the reserved
loopback host `id.usuals.localhost:8091`.

Choose **Create your list**, enter email, open the verification message in
Mailpit, and set up a password in Keycloak. Create a list and use **Share** to
invite `bob@example.test`. Open Bob's invitation in another browser profile, or
use the landing page's **Switch account** action. Register Bob, verify email,
then accept. Bob receives only that list. Both accounts can edit it.

To test beta admission, start with `--invitation-only`. The generated protected
credentials file contains a synthetic operator account. Sign in as that account
and use **People > Invite beta testers** to admit a first person. That person
can invite collaborators directly from a list. Starting a provider account
through a direct registration URL does not bypass the server's admission gate.

The launcher prints the private output directory and `stack.json` path. Run
`node server/scripts/test-email-preview.mjs <stack.json>` for the HTTP smoke
against an open-registration local stack. It creates disposable accounts through
the actual email/credential flows, checks wrong-account rejection, explicit
acceptance, list isolation, duplicate acceptance and a shared list edit. It
keeps generated credentials in a mode-0600 file and prints no credentials.
Ctrl-C removes the containers. Databases, local identities and sessions are
disposable. Do not use real data in this preview.

## Operator configuration

| Setting | Default / meaning |
| --- | --- |
| `USUALS_OIDC_REGISTRATION_ENABLED` | `false`; exposes `/auth/register`, requesting the provider's standard `prompt=create` flow |
| `USUALS_EMAIL_INVITATIONS_ENABLED` | `false`; enables invitation APIs and the email Share sheet; requires OIDC |
| `USUALS_INVITATION_ONLY` | `false`; require a valid recipient-matching invitation before creating a new OIDC account/session, except explicit administrator subjects |
| `USUALS_DIRECTORY` | `all` preserves the single-group self-hosted directory; `collaborators` returns only active shared-list collaborators and blocks adding unrelated UUIDs directly |
| `USUALS_SMTP_HOST`, `USUALS_SMTP_FROM` | Required when mail is enabled; operator-controlled SMTP destination and sender mailbox |
| `USUALS_SMTP_PORT` | `587` |
| `USUALS_SMTP_STARTTLS` | `true`; required on production origins, with certificate/hostname checks |
| `USUALS_SMTP_USERNAME`, `USUALS_SMTP_PASSWORD_FILE` | Optional paired authentication; password from a protected regular file, never a command argument or URL |
| `USUALS_INVITATIONS_PER_SENDER_MINUTE` | `10`, durable across replicas |
| `USUALS_INVITATIONS_PER_RECIPIENT_MINUTE` | `2`, shared across senders |
| `USUALS_INVITATIONS_PER_MINUTE` | `60`, instance mail budget |
| `USUALS_OIDC_LOCAL_HTTP` | `false`; explicit local QA only: a loopback HTTP application origin and loopback/`.localhost` IdP endpoints are both required |

Keep registration and invitation email in the same transactional provider if
convenient, with authenticated sender DNS and tracking disabled. Configure the
identity provider to verify email, accept email as its login identifier and
avoid required surname/username fields. The local launcher demonstrates the
provider configuration without changing any real realm or credentials. Native
passkeys and social sign-in remain provider choices.

For Keycloak, retain the default `basic` client scope alongside `profile` and
`email`: it supplies the required `sub` and `auth_time` protocol mappers. See
[Keycloak's upgrade guide](https://www.keycloak.org/docs/26.5.7/upgrading/).

Production with unrelated users should enable `collaborators` and verified
email invitations together. This restricts discovery; existing list ACLs remain
the content boundary. Administrator account tools can enumerate accounts but
cannot read lists. Invitation-only mode admits initial testers through operator
mail and collaborators through list invitations, without making either an admin.
Existing enabled accounts retain their normal sign-in.

## HTTP contract

All replies are private/no-store. Mutation bodies are bounded JSON and browser
account mutations use the existing exact Origin and CSRF checks.

| Method / path | Behavior |
| --- | --- |
| `GET /auth/config` | Also reports `registrationPath?`, `emailInvitations`, `invitationOnly` |
| `GET /auth/register` | Provider registration, with ordinary state/nonce/PKCE and browser binding |
| `GET /auth/login?fresh=true` | Force provider login with `prompt=login`, request `max_age=300` and require a valid recent signed `auth_time`; account switching cannot reuse the previous SSO identity silently |
| `GET /api/invitations/challenge` | Bounded 30-minute HttpOnly continuation cookie and CSRF challenge |
| `POST /api/invitations/context` | `{token}` with that cookie, challenge CSRF and exact Origin; attaches a link without logging in or adding membership |
| `GET /api/invitations/current` | `none`, `signin`, `ready`, `wrong_account`, `reauthenticate`, `accepted` or `unavailable`; no list content |
| `POST /api/invitations/accept` | Explicit authenticated acceptance; returns `{list?}` from the existing list implementation |
| `POST /api/invitations/dismiss` | Same-origin dismissal of the browser's pending continuation |
| `GET/POST /api/lists/{id}/invitations` | List members view pending status or send `{email,locale?}` |
| `POST /api/lists/{id}/invitations/{invitationId}/resend` | Sender/owner rotates the secret after a 60-second delay |
| `DELETE /api/lists/{id}/invitations/{invitationId}` | Sender/owner revokes a pending invitation |
| `GET/POST /api/admin/invitations` | An OIDC administrator lists/sends operator admission invitations, without list access |
| `POST/DELETE /api/admin/invitations/{invitationId}[/resend]` | Same lifecycle for the administrator's own admission invitations |

No account-existence lookup is exposed by sending an email invitation. ASCII
email addresses are compared case-insensitively; dots and plus tags remain
significant. Identities are still keyed by `(issuer, subject)` and never merged
by email. A session stores its own signed verified-email and `auth_time` proof;
updated profile metadata or another session's login cannot supply that proof.

Links contain a random 32-byte secret in the fragment. The web app removes the
fragment before requests and never persists it in browser storage. A continuation
is bound to the browser and then the OIDC transaction; callbacks still return to
`/`. GETs and email-scanner previews do not consume invitations, issue sessions
or change membership. Cross-device verification must retain normal provider and
browser-binding checks; restart in the intended browser if needed.

Acceptance locks the list before the invitation, verifies current sender
membership, recipient proof, expiry, enabled accounts and capacity, then uses the
same membership mutation and feed sequence in one transaction. Replays by the
accepting member are idempotent. An accepted invite cannot restore a membership
after removal or leave. Sender removal, list deletion, cancellation, expiry,
resend and restore invalidate pending access.

## Storage and delivery

Migration 009 adds invitation/continuation records and per-session identity
proof. The invitation table is also a durable mail outbox. It holds token hashes
and an encrypted retry payload bound to a separate mail context under the
existing at-rest key. The existing key-check and `list-rewrap` operations cover
mail payloads too. SMTP-accepted and terminal payloads are cleared. Old invitation
metadata is purged 30 days after expiry; continuations expire after 30 minutes.

A bounded background worker locks one queued row with `SKIP LOCKED`, rechecks
the pending invitation and sends using Jakarta Mail. TLS/connection/read/write
and total-attempt deadlines bound delivery work. Temporary failures retry with
backoff; eight failed attempts expose `failed` to the sender. A restart can
duplicate email if SMTP accepted before a database failure. It cannot duplicate
membership. Resending rotates the token; old messages/continuations stop working.
The UI distinguishes queued mail, SMTP acceptance and recipient acceptance.

Verification runs through `server/scripts/test-local`: recent email proof,
wrong account, expiry/resend/revoke, admission bypass, directory isolation,
concurrent acceptance, durable retry, restore, key rotation, HTTP CSRF and
mailbox/header validation. The real-provider smoke complements those deterministic
tests. Run the full Docker product suite after changes to shared behavior.
