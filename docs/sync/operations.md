# Local S0 operations inventory

Observed 2026-10-16 from local files only, at Vito's direction. These are
repository conventions, not a claim about live cluster health. Other repos
contain local work; no file in them was edited. No kubeconfig, secret contents,
SSH connection or Kubernetes API was used.

## Existing services

| Evidence | Observation | Usuals decision |
|---|---|---|
| `theficos-cluster/README.md`, HEAD `9a43573` | Single-node MicroK8s; Kustomize; Traefik; cert-manager; deSEC DNS; SOPS/age | Follow this stack in a later reviewed deployment. |
| `spunto/deploy/k8s/README.md` and `ingress.yaml`, HEAD `2fd2fcb` with local changes | Node `theficos`, amd64, `microk8s-hostpath`, Traefik `websecure`, ClusterIssuer `letsencrypt-prod`, service hostname under `theficos.dedyn.io` | Propose `usuals.theficos.dedyn.io`, namespace `usuals`; DNS and certificate availability require live validation after deployment approval. |
| `spunto/deploy/k8s/deployment.yaml` | One replica, immutable locally imported image, imagePullPolicy Never, UID/GID 10001, no service token, read-only filesystem, dropped capabilities, startup/readiness/liveness probes | Use local image import rather than registry publication; render content-addressed references only. No image import now. |
| `theficos-cluster/applications/quire-server/postgres-statefulset.yaml`, `postgres-pvc.yaml` | Dedicated PostgreSQL 16 instance and RWO 5 GiB PVC in calibre-web namespace | Use a separate Usuals database/role and dedicated RWO disk; do not reuse Quire credentials or alter its database. A new PostgreSQL 16 instance is the default until approved DBA reuse. |
| `quire/server/README.md`, HEAD `eebaf0f` with local changes | Separate server, `/health` and `/readyz`, migration step and explicit server configuration | Keep probes generic, migrations explicit, configuration environment-based with secret-file inputs. No retailer/model proxy or inherited auth. |
| `spunto/deploy/k8s/shared-storage.yaml`, `README.md` | Separate backup directory on cluster-data filesystem, NFS for backup artifacts, Borg integration | Backups may use the same off-database storage convention. Never put live PostgreSQL data on that NFS share. |
| `theficos-cluster/scripts/backup/config.yaml` capability-only inspection | Borgmatic, PostgreSQL dump hooks, daily retention/scheduling | Existing daily dumps do not establish 15-minute RPO or WAL recovery. Add a reviewed Usuals dump/archive job and restore drill; no current backup guarantee. No secret values were read into this record. |

Future deployment review must verify DNS A/AAAA, public 80/443, issuer readiness,
Traefik WebSocket and forwarded-header handling, namespace network-policy
selectors, node disk/memory capacity, non-root volume permissions, database TLS
if outside the dedicated namespace and off-cluster Borg restore capability.
Trust forwarded IPs only from known ingress peers; rate limits must not trust
arbitrary X-Forwarded-For. Do not copy Spunto's Ollama mounts/configuration.

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

Start relay at 100m CPU/256 MiB request, 1 CPU/512 MiB limit; database at
500m/512 MiB request, 1 GiB memory limit and 10 GiB RWO hostpath storage.
Measure heap, native memory, connections and load before approving those limits.
One relay replica and one database instance, with bounded worker/connection
queues. Restarted sockets resume by cursor; no Redis or task broker.

Proposed recovery targets remain 15-minute RPO and 2-hour RTO. They require WAL
archive/base-backup design plus off-cluster retention and measured restore.
Until that is implemented and drilled, describe backups as unverified for
Usuals. V1 restore fences old households before authentication, not merely their
sessions. Recovery uses a verified local operator export grant and a reviewed
copy into a fresh household with new keys/invites. Old household ids never
resume. Keep the restore fence outside restored database state, persist the
receipt signing key separately, and fail startup if that identity key is lost.
S5 must test old sessions and fresh challenges from a removed device after a
roster-only rollback, not merely restore PostgreSQL rows. Clients preserve
membership epoch/hash pins separately from list-head pins.

Local S0 can proceed from this inventory. Applying manifests, provisioning DNS,
creating database users, decrypting/applying SOPS files, importing images and
changing backup jobs all require Vito's deployment approval first.

S5 now provides the [local deployment package](../../deploy/README.md),
[restore runbook](runbook.md) and [offline recovery-copy contract](recovery-copy.md).
Base/WAL recovery and bounded container smoke checks passed locally; live
inventory, encrypted off-cluster coverage/retention, RPO/RTO and the week pilot
remain approval and measurement gates. See the S5 release evidence.
