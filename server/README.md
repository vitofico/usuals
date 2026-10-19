# Usuals relay

Local relay implementation. The encrypted household client and Android
shopping transport connect through the reviewed v1 contract. Cluster deployment
and the household pilot remain separate gates.
This isolated Kotlin/Ktor build compiles the shared `:sync` and `:core:model`
sources with its own compiler, outputs and dependency locks. No Android or Room
libraries enter this process. The Android toolchain stays unchanged.

Run the real PostgreSQL integration suite and build the distribution:

```sh
server/scripts/test-local
```

The helper creates a disposable loopback-only PostgreSQL container with an
in-memory disk, generates temporary credentials and invokes
`scripts/dgradle -p server test installDist --no-watch-fs`.
Cleanup removes the container and credentials even after a failed test. Tests
fail rather than silently skip if PostgreSQL configuration is missing. The helper does not regenerate locks by default; pass `--write-locks`
only when intentionally updating dependencies.

## Runtime

Build with `scripts/dgradle -p server installDist`. The distribution is under
`server/build/install/usuals-relay/`. Its CLI has these modes:

- `init-identity`: explicitly create a receipt signing identity and independent
  restore fence file, both mode 0600. Refuse existing files.
- `migrate`: checksummed SQL migrations under a PostgreSQL advisory lock.
  Reject changed applied SQL and newer database versions.
- `bootstrap-token`: create a one-use 24-hour operator bootstrap token. This
  explicit command emits the secret once; redirect it to a protected local
  file. Never use shell tracing or include the token in a URL.
- `restore-fence`: with traffic stopped, durably replace the external fence then
  apply the freeze/revocation transaction before successful exit. Restart safely
  reapplies the fence after interruption. Never unfreeze restored households.
- `recovery-export GRANT_FILE NEW_OUTPUT_PATH`: offline frozen-household export
  of signed ciphertext to an independently verified historical owner/recovery
  recipient. Protected POSIX grant/directory with SecureDirectoryStream support
  in the Linux runtime container, immutable published output, no session.
- No arguments: start the HTTP relay after verifying its persistent identity
  and applying the independent restore fence. No implicit migration/key reset.

Configuration is environment-based:

Optional local accounts and Authentik SSO, session management and immutable
browser device binding are described in [web identity](../docs/sync/web-identity.md).
They are disabled by default. Sign-in alone grants no household keys or recovery
access. An admitted account may explicitly create a new household through the
same-origin CSRF-protected web endpoint; genesis and owner-device account binding
commit atomically. Creation is limited to five attempts per minute and ten
permanent created-household associations per account. Existing Android bootstrap
still uses its one-time operator code. Apply migration 004 before starting this
version against an existing database.

| Variable | Value |
|---|---|
| `USUALS_IDENTITY_FILE` | Persistent receipt key file created explicitly |
| `USUALS_FENCE_FILE` | Persistent fence file kept outside database restores |
| `USUALS_JDBC_URL` | Dedicated PostgreSQL JDBC URL |
| `USUALS_DB_USER` | Dedicated database role |
| `USUALS_DB_PASSWORD_FILE` | Read-only mounted password file |
| `USUALS_ORIGIN` | Exact canonical public HTTPS origin |
| `USUALS_OPS_BIND` / `USUALS_OPS_PORT` | Private aggregate metrics listener, default127.0.0.1:9090; never public ingress |
| `USUALS_TRUSTED_PROXY_CIDRS` | Explicit immediate trusted ingress peer CIDRs; unset ignores forwarded headers |
| `PORT` | Listener port, default 8080 |
| `USUALS_LOCAL_ONLY` | Explicit `true` permits HTTP loopback/emulator origins and binds loopback for local development |

`USUALS_ORIGIN` is signed into authentication proofs. Reverse-proxy headers do
not control it. Forwarded IP headers from untrusted peers are ignored. An explicitly configured
trusted single ingress hop contributes only its final appended client address. Production TLS is
terminated by the approved ingress; no public deployment is configured here.

## Guarantees and limits

The relay stores membership/public keys, routing, hashed bearer/session/invite
secrets and opaque encrypted operations, packages and snapshots. It never
parses shopping contents. Bodies, credentials and database parameters are not
logged. There is no access-log plugin. Capacity errors are structured codes.

A PostgreSQL household row lock precedes list locks in UUID order. Sequence
allocation, the encrypted operation, hash-chain head and signed receipt commit
together. Failed batches allocate no durable cursor. Exact retries reuse the
receipt. Intent collisions return only previously committed receipts, never a
receipt allocated by the rejected batch. Catch-up is contiguous and paginated.
No event/tombstone compaction exists.

Signed membership CAS verifies the preceding owner or restricted recovery
principal, every staged snapshot/head and every recipient package. It rotates
all lists together, marks removed memberships inactive and revokes sessions.
Sockets check current authorization every second and after change hints.
Sessions expire after 15 minutes, recovery sessions after five. Recovery cannot
append or invite. The complete wire contract is in [../docs/sync/](../docs/sync/).

Requests have encoded limits before parsing, strict UTF-8/JSON and duplicate-key
checks, bounded depth/nodes, a 15-second deadline and eight active request slots. One bulk call is admitted
through response serialization, after authenticated routing-header checks.
JDBC uses four connections, a dispatcher admitting at most four JDBC operations, five-second statements
and two-second lock/acquire limits. WebSocket frames are at most 16 KiB, with
32 pending handshakes, 320 connected sockets and two per device. Rate buckets
and ciphertext quotas are durable. Staged candidates expire and are bounded.

A changed external restore fence permanently freezes every restored household
before authentication, invalidates transient authority and never resumes old
IDs. A lost receipt key also fails startup. Operators cannot reset encryption
keys. The [local deployment package](../deploy/README.md), [recovery-copy contract](../docs/sync/recovery-copy.md)
and [runbook](../docs/sync/runbook.md) describe restore/reconciliation and its
remaining live gates before a pilot. Run `server/scripts/drill-local` and
`server/scripts/profile-local` only against their disposable local containers.

The local [deployment package](../deploy/README.md) includes an offline bundle
preparer/verifier, separate identity/migration jobs and a nonsecret inventory
example. `server/scripts/profile-local` also exercises authenticated near-limit
snapshot admission using the explicitly invoked wrapper `profileClient` task.
No preparation command deploys, imports to a cluster or pushes an image.
