# Operations

Conventions for running the Usuals server and the pins it was built against.
The [deployment package](../../deploy/README.md) implements them.

## Deployment checks

Future deployment review must verify DNS A/AAAA, public 80/443, issuer readiness,
Traefik forwarded-header handling, change-feed requests held open for up to 25
seconds, namespace network-policy selectors, node disk/memory capacity, non-root
volume permissions, database TLS if outside the dedicated namespace and
off-cluster Borg restore capability.
Trust forwarded IPs only from known ingress peers; rate limits must not trust
arbitrary X-Forwarded-For.

## Version pins

Maven versions live in `gradle/libs.versions.toml`, including future relay pins.
Current Android compiler/dependencies stay unchanged. Ktor 3.6.0 is built with
[Kotlin 2.3.21](https://github.com/ktorio/ktor/blob/3.6.0/gradle/libs.versions.toml),
so it cannot simply be added to the existing Kotlin 2.0.20 Android graph.

S2 uses a separate `server/` JVM build, invoked with
`scripts/dgradle -p server test`. Its version catalog imports the root catalog
and overrides only Kotlin to `relay-kotlin`. The future pure `:sync` source is
included in both builds and compiled under each compiler, with the same wire
fixtures. Server dependencies never enter the Android graph. No skipped
metadata checks and no Android/KSP upgrade as an incidental server change.
[Kotlin's compatibility table](https://kotlinlang.org/docs/gradle-configure-project.html)
supports Gradle 8.10.2 with 2.3.21. S2 must execute both builds and lock resolved
transitives before calling compatibility verified.

| Component | S0 pin | Evidence |
|---|---|---|
| Gradle/JDK | Existing wrapper 8.10.2, Java 21 | Local wrapper and Dockerfile; no toolchain change |
| Relay Kotlin/Ktor | 2.3.21 / 3.6.0 | Tagged Ktor source above; [release](https://ktor.io/changelog/3.6/) |
| Tink | 1.23.0, test-only in S0 | [Official release](https://github.com/tink-crypto/tink-java/releases/tag/v1.23.0) |
| PostgreSQL JDBC | 42.7.14 | [Official driver release](https://jdbc.postgresql.org/) |
| HikariCP | 7.1.0, pool max 4, acquire timeout 2 seconds | [Official README](https://github.com/brettwooldridge/HikariCP); Java 11+ |
| PostgreSQL image | `postgres:16.15-alpine@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea` | Read registry manifest digest on 2026-10-16, no pull/run |
| Relay runtime base | `eclipse-temurin:21-jre-jammy@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b` | Read registry manifest digest on 2026-10-16, no pull/run |

All five pinned Maven POMs returned HTTP 200 from Maven Central during S0;
their hashes are retained in local evidence. This verifies publication, not
future relay build compatibility.

These are immutable multi-platform manifest references; the target is amd64.
Revalidate support/security before deployment and update pins explicitly.
The application's built image gets its own digest/content fingerprint later.
Use numbered SQL migrations with a checksummed migration ledger and a dedicated
startup migration command under an advisory lock. Reject modified applied SQL
or newer schemas. No migration framework dependency is selected implicitly.

## Resource and recovery targets

Start relay at 100m CPU/256 MiB request, 1 CPU/768 MiB limit; database at
500m/512 MiB request, 1 GiB memory limit and 10 GiB RWO hostpath storage.
Measure heap, native memory, connections and load before approving those limits.
One server replica and one database instance, with bounded worker/connection
queues. Clients resume the change feed by cursor; no Redis or task broker. The
waiting feed requests live in that one process, so a second replica would need a
shared notifier.

Proposed recovery targets remain 15-minute RPO and 2-hour RTO. They require WAL
archive/base-backup design plus off-cluster retention and measured restore.
Until that is implemented and drilled, describe backups as unverified for
Usuals. A restore rotates the external fence before anyone authenticates: every
session and phone connection is revoked and every client's list feed starts over
([ADR 0017](../decisions/ADR-0017-server-held-lists.md)). Keep the restore fence
outside restored database state, keep the server identity and the list key in
separate escrow, and fail startup if either is lost.

Applying manifests, provisioning DNS,
creating database users, decrypting/applying SOPS files, importing images and
changing backup jobs all require the operator's deployment approval first.

S5 now provides the [local deployment package](../../deploy/README.md) and
[restore runbook](runbook.md). Base/WAL recovery and bounded container smoke checks passed locally; live
inventory, encrypted off-cluster coverage/retention, RPO/RTO and the week pilot
remain approval and measurement gates. See the S5 release evidence.
