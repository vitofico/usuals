# Usuals relay

The account server. It holds every list of an account, applies list changes
with the shared reducer and lets accounts share lists with each other
([ADR 0017](../docs/decisions/ADR-0017-server-held-lists.md), [wire contract](../docs/sync/lists-v2.md)).
This isolated Kotlin/Ktor build compiles the shared `:sync` and `:core:model`
sources with its own compiler, outputs and dependency locks. No Android or Room
libraries enter this process. The Android toolchain stays unchanged.

Run the real PostgreSQL integration suite, the browser unit tests and build the distribution:

```sh
server/scripts/test-local
```

For real email registration and list invitations with local captured email, run
`node server/scripts/email-preview.mjs`. See the
[email onboarding guide](../docs/sync/email-invitations.md) for the complete
registration, invitation, beta-admission and local test workflow.

The helper creates a disposable PostgreSQL container on a private Docker network,
with an in-memory disk and temporary credentials. It invokes
`scripts/dgradle -p server test webTest installDist --no-watch-fs`; `webTest` runs
`node --test web/test/*.test.mjs`. Cleanup removes the container, network and
credentials even after a failed test. Tests fail rather than silently skip if
PostgreSQL configuration is missing. The helper does not regenerate locks by
default; pass `--write-locks` only when intentionally updating dependencies.

`server/scripts/preview-local` serves the web app against a disposable database
with two local accounts sharing one list. Their generated passwords are written
to `credentials.json` (mode 0600) in a new git-ignored `server/build/browser-preview.*` directory and
never printed.

## Runtime

Build with `scripts/dgradle -p server installDist`. The distribution is under
`server/build/install/usuals-relay/`. Its CLI has these modes:

- `init-identity`: explicitly create a server identity and independent
  restore fence file, both mode 0600. Refuse existing files.
- `migrate`: checksummed SQL migrations under a PostgreSQL advisory lock.
  Reject changed applied SQL and newer database versions.
- `restore-fence`: with traffic stopped, durably replace the external fence then
  revoke every session and transient credential before successful exit. The new
  fence is also the list feed epoch, so every client reads its lists again.
- `local-admin USERNAME SETUP_FILE` / `local-reset USERNAME SETUP_FILE`: create or
  reset a local operator account and write its one-use setup token to a new
  protected file.
- `list-rewrap OLD_KEY_FILE NEW_KEY_FILE`: with traffic stopped, re-encrypt every
  stored list under a new key in one transaction, then point
  `USUALS_LIST_KEY_FILE` at the new file. `ai-rewrap` does the same for AI keys.
- No arguments: start the HTTP server after verifying its persistent identity,
  applying the independent restore fence and checking that the list key opens
  the stored lists. No implicit migration or key creation.

Configuration is environment-based. Local accounts and Authentik SSO are
described in [web identity](../docs/sync/web-identity.md); they are disabled by
default, and the list routes exist only when one of them is enabled.

| Variable | Value |
|---|---|
| `USUALS_IDENTITY_FILE` | Persistent server key file created explicitly |
| `USUALS_FENCE_FILE` | Persistent fence file kept outside database restores |
| `USUALS_LIST_KEY_FILE` | Required 32-byte list encryption key: a regular file, not a symlink, with no group or other permissions (create with `head -c 32 /dev/urandom`, mode 0600). Startup fails without it or when it cannot open the stored lists |
| `USUALS_JDBC_URL` | Dedicated PostgreSQL JDBC URL |
| `USUALS_DB_USER` | Dedicated database role |
| `USUALS_DB_PASSWORD_FILE` | Read-only mounted password file |
| `USUALS_ORIGIN` | Exact canonical public HTTPS origin |
| `USUALS_OPS_BIND` / `USUALS_OPS_PORT` | Private aggregate metrics listener, default 127.0.0.1:9090; never public ingress |
| `USUALS_TRUSTED_PROXY_CIDRS` | Explicit immediate trusted ingress peer CIDRs; unset ignores forwarded headers |
| `PORT` | Listener port, default 8080 |
| `USUALS_LOCAL_ONLY` | Explicit `true` permits HTTP loopback/emulator origins and binds loopback for local development |

`USUALS_ORIGIN` is the only origin accepted on browser mutations. Reverse-proxy headers do
not control it. Forwarded IP headers from untrusted peers are ignored. An explicitly configured
trusted single ingress hop contributes only its final appended client address. Production TLS is
terminated by the approved ingress; no public deployment is configured here.

## Guarantees and limits

The server reads list contents to apply changes, so it is trusted with them. Each
stored list is sealed with AES-256-GCM under the list key, a fresh nonce and the
list id as associated data, so a database copy or backup alone does not reveal
it and a ciphertext moved onto another list fails to open. Lists are decrypted
only in memory. Account names and emails stay plaintext. Bodies, credentials and
database parameters are not logged. There is no access-log plugin.

One locked counter row orders every list change, so the change feed never skips a
commit. A batch of intents runs through the reducer under the list row lock and
bumps the list sequence only when it changed something. Non-members get 404.
Browser sessions slide to 30 days from use and phone sessions to 180 days, each
renewed at most once a day.

Requests have encoded limits before parsing, strict UTF-8/JSON and duplicate-key
checks, bounded depth/nodes, a 15-second deadline and eight active request slots.
A stored list is at most 7 MB encoded. Building list responses is limited to two
at a time, and large ones are gzip-compressed. A waiting change-feed request
holds no slot while it waits, at most 25 seconds, with four per account and 256
in total. One process holds every waiter, so a second replica needs a shared
notifier. JDBC uses four connections, a dispatcher admitting at most four JDBC
operations, five-second statements and two-second lock/acquire limits. Rate
buckets are durable.

A changed external restore fence revokes every session and transient credential
before authentication and resets every client's list feed. A lost server key or
list key fails startup. The [local deployment package](../deploy/README.md) and
[runbook](../docs/sync/runbook.md) describe restore and its remaining live gates.
Run `server/scripts/drill-local` and `server/scripts/profile-local` only against
their disposable local containers. `server/scripts/profile-local` also exercises
anonymous and authenticated near-limit list uploads under the proposed container
limits. No preparation command deploys, imports to a cluster or pushes an image.

### Optional browser photo models

`USUALS_WEB_MODEL_ORIGINS` defaults to `[]`. To enable direct browser model
connections, supply a JSON array of exact HTTPS origins, without paths or
credentials. The provider must accept the app origin through CORS. This only
changes the browser CSP and public allowed-origin list; no server model proxy
or credential store is created. See [provider setup](../docs/providers.md).

## Account connection and optional AI

The [connection guide](../docs/server-connection.md), [provider configuration](../docs/providers.md)
and [additive service API](../docs/server-services.openapi.json) cover native account
approval and the optional model gateway. A phone that claims a connection offer
shown by a signed-in browser is approved at once. The gateway is disabled with no configured
provider profiles, and its availability does not determine relay readiness.

The optional [account policy and lifecycle interfaces](../docs/sync/account-policy-and-lifecycle.md)
cover per-account limits, paginated export, fresh closure authorization and
operator execution keys.
