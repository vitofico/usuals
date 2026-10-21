# Shared-list relay runbook

Use an explicit deployment authorization before applying these steps. Record
source commit, image digest, config and operator at each deployment in a private
operator record, never in this repository. Do not put secrets in command
arguments, logs or Git.

## First deployment

1. Confirm MicroK8s version/admission, the target amd64 node, available RAM/
   disk, hostpath storage, NFS reachability/private UID70 directory, Traefik labels,
   cert-manager issuer and intended DNS/hostname. Confirm HTTP redirects to TLS.
   Verify CNI NetworkPolicy enforcement and Traefik's sanitized/appended forwarding
   behavior. Set USUALS_TRUSTED_PROXY_CIDRS to the verified immediate ingress
   peer address range; the checked-in marker fails startup until replaced.
   The relay uses only the rightmost X-Forwarded-For address from that trusted
   direct hop, ignores earlier client claims and ignores headers from any other
   peer. Keep this single-hop assumption explicit; adding a CDN needs review. The manifests use the observed local
   service conventions; they are not evidence of the current live state.
2. Generate private random admin/app/backup passwords and a correct escaped
   `backup-pgpass` file. Fill `deploy/k8s/secrets.example.yaml` privately and
   encrypt with the established SOPS/age process outside Git. Do not apply
   placeholders or send credentials to an assistant. Keep app and replication
   users separate. Bootstrap SQL reads mounted files, not password arguments.
3. Build/test locally, record immutable image/distribution hashes, approve and
   import the image. Set the exact same reference in deployment and both explicit
   jobs. Apply namespace, private secrets, storage, tools and database first.
   Inspect readiness and the least-privilege roles. Initialization runs only on
   a fresh PGDATA; interrupted initialization requires inspection, not blind retry.
4. Run `jobs/initialize-identity.yaml` once. It refuses existing key/fence files.
   Securely escrow the receipt signing key separately from database backups.
   Never restore or roll back the current fence file with database state.
   Run `jobs/migrate.yaml`, then start the relay/service/network policies/ingress.
   Applied migration checksums and newer schemas fail closed; startup never
   migrates implicitly. A failed initial job must be inspected before recreation.
5. Verify TLS/origin, readiness/liveness, no public metrics, actual network denial,
   read-only filesystem, non-root users, PVC durability and restart behavior.
   Obtain a one-use bootstrap token via an explicit operator exec with stdout
   redirected directly to a protected file. Never run that command as a Job
   whose stdout becomes Kubernetes logs. Give it to the known owner privately.
6. Complete off-cluster backup/restore and revocation drills before real pilot
   data. Then perform an authorized physical-phone pass and start the two-person
   week-long pilot. Invite more people only after no unexplained loss/duplication.

## Backup and recovery targets

Proposed targets: at most 15 minutes lost and restoration within two hours.
Local Docker timings establish tool behavior, not off-cluster or cluster RPO/RTO.
PostgreSQL uses native [continuous WAL archiving](https://www.postgresql.org/docs/16/continuous-archiving.html)
and [pg_basebackup](https://www.postgresql.org/docs/16/app-pgbasebackup.html).
`archive_timeout=60s` bounds low-traffic segment switching while PostgreSQL is
healthy. The archive script compresses, persists and publishes each segment
without overwriting a conflicting name. Identical retries succeed. Weekly base
backups stream WAL, use SHA256 manifests and run `pg_verifybackup` before
publication. Take the first complete verified base backup immediately; do not
wait for the weekly schedule. Missing WAL or an unverified base is not a backup.

After approval, adapt the scoped Borgmatic template to the installed version
and the existing encrypted off-cluster repository. The five-minute timer has a
four-minute run deadline. Monitor actual successful archive completion and
recoverable WAL coverage, not just scheduled starts. A successful encrypted
archive followed by `offsite-completed-at` is the completion marker. Page at
10 minutes of stale off-site completion; treat 15 minutes as an RPO breach.
Also alert on archiving errors/lag >2 minutes, base age >8 days, backup-job failure,
PG/live/NFS disk >70% warning/>85% critical and missing receipt-key escrow.
Check these from the cluster's existing monitoring route after approval.
Do not claim the target until a post-base synthetic commit is recovered from
the encrypted off-cluster archive and its measured age is below 15 minutes.
Borg credentials and encrypted repository configuration are operator-owned.

During the seven-day pilot keep every complete base and WAL recovery set. No
automatic pruning ships. Plan a 30-day recovery window after verifying off-site
coverage: retain the oldest necessary base plus all WAL/timelines through the
window, prune complete sets only, and test `pg_archivecleanup` dry runs before
changing host retention. Do not delete live `pg_wal`. Add reviewed capacity and
retention automation before widening the pilot. A WAL failure retains live WAL
until disk fills, so disk/archiver alerts are an availability requirement.

## Restore, lost node, or roster rollback

1. Stop ingress/relay traffic and the relay process. Preserve surviving phones'
   newer lists/pending edits and private pins. Save protected incident evidence.
   Select a verified base and continuous archived WAL from the encrypted off-site
   copy. Restore into an isolated new PG data volume using the same PostgreSQL
   major version. Never mount live PG data over NFS. Do not expose recovered PG
   or HTTP while restoring. Use recovery.signal and a restore_command that
   decompresses the requested archived WAL; select and verify the recovery target.
2. Preserve the independently escrowed receipt identity and the current external
   fence. If identity is missing/mismatched, stop. Create a separate fresh service
   identity/origin only through explicit reviewed migration; old signatures cannot
   be fabricated. Never silently regenerate it. Recreated empty fence storage
   also requires a fresh fence, not a fence copied from the database backup.
   The explicit restore-fence command can create that absent file while preserving
   the existing escrowed identity; ordinary startup still refuses a missing file.
3. With traffic still stopped, run `restore-fence` offline against restored DB
   and a writable identity/fence volume. It first validates the supported schema and matching receipt identity without
   changing the external fence. It then atomically fsyncs a fresh UUID file,
   then applies the fence in a DB transaction before successful exit. A crash
   between those stages is safe only while traffic is stopped: rerun/startup
   reapplies the external fence before authentication. It permanently freezes
   old households, revokes sessions, deletes challenges/bootstrap tokens/staging
   and consumes/denies old invitations/join requests. Missing DB metadata with
   existing state and mismatched receipt identities fail closed. Do not unfreeze
   old ids or restore old sessions, even if a head appears unchanged.
4. Test an old bearer and fresh session/recovery challenges, including a revoked
   device resurrected by a roster-only rollback. All old households must remain
   denied. Verify those tests before restoring HTTP ingress. `restore-fence` is
   an operator command, not an online reset endpoint.
5. Obtain a [verified public request/grant](recovery-copy.md) from the known owner.
   Run `recovery-export GRANT_FILE NEW_OUTPUT_PATH` offline against the frozen
   DB. Use a mode 0700 child directory owned by UID 10001 on a dedicated writable
   export volume with at least 300 MiB free for records plus the staged final file.
   The routine pod's 32 MiB temporary mount is insufficient for maximum exports.
   Keep the protected grant mode 0600 and no-overwrite output semantics. Transfer
   the ciphertext file privately. The operator never receives the recovery kit
   or its separate secret. Remove abandoned `.usuals-export-*` staging only
   after confirming the exporting process is stopped; retry to a new final path.
6. On the phone select **Open encrypted server backup**, open the matching kit
   and enter its separate key if needed. Read older/ancestry warnings, review
   live item/history counts and save an independent local copy. Existing work
   and private pins remain. Reconcile explicitly, then create a fresh household,
   share the reviewed copy with fresh keys and issue fresh invitations.

## Upgrade and rollback

Before an upgrade, record image/distribution/config hashes and take a verified
backup. Migrate with stopped relay traffic and a single explicit migration job.
For an application-only rollback, stop traffic and run the prior tested image
against the same unchanged schema, identity and fence. Never roll back private
identity/counters or database rows as an ordinary app rollback. Every normal
startup and database CLI path checks the exact supported migration ledger
before fencing or serving authentication; it rejects unknown/changed versions.
Explicit restoration also checks receipt identity before touching its external
fence. A database restore, including a partial roster rollback, always follows
the full freeze/reconcile procedure above.
PostgreSQL patch upgrades stay within 16 and require a restore rehearsal. A major
upgrade needs its own plan; never start a different major on the old data dir.

## Operations and deletion

The public port exposes `/healthz` and DB-aware `/readyz`. Private port 9090
exposes aggregate `/metrics` only, with no Service/Ingress/allow rule. Use an
authorized port-forward or explicitly reviewed monitoring rule. Counters include
request errors/duration buckets, active work/sockets, JDBC pool occupancy, heap
and aggregate database/ciphertext storage. No household/device/IP labels or
shopping payloads. The phone's unsent queue age is private and is not uploaded.
Backups/WAL/off-site freshness and node memory/disk need host monitoring.

Eight ordinary calls and one bulk membership/snapshot/catch-up call are admitted
without an unbounded application wait queue. Bulk permits last through parsing,
DB and response serialization. Four JDBC connections, 15-second HTTP deadlines,
five-second statements and two-second lock/acquire limits bound work. Netty
uses 1 connection, 2 worker and 4 call threads. Resource requests are starting budgets;
local profile evidence does not replace amd64 node/load monitoring. Alert on
sustained 5xx, two-second foreground latency breaches, JDBC waiters and memory
>80% of 512 MiB. Background delivery remains Android-scheduled and eventual.

For deletion, remove members/rotate while a surviving owner exists, retain or
export unsent local work explicitly, then stop the service for an operator-owned
household content purge if requested. V1 has no public household-deletion API;
do not claim one. Freeze that household permanently and retain its household-id,
public principal/membership guard rows and receipt identity/fence. Purge encrypted
content/packages/snapshots/operations and transient invites/requests/sessions in
one reviewed transaction. Never delete the frozen-id/revocation guard: doing so
would allow a historical signed genesis to reuse that id with a new bootstrap
capability. Verify old bearer, fresh proof and old-genesis bootstrap denial. Historical encrypted backups expire only through the reviewed
retention process, not immediate erasure. Document the maximum actual expiry;
before a 30-day policy is deployed, pilot backups require an explicit operator
purge. Removed devices may retain data already decrypted.

Record the week pilot's start/end, devices/build hashes, offline intervals,
Finish/Undo/conflict observations, backup restore and duplication/loss reports.
Physical tests, live CNI/TLS/storage, encrypted off-site recovery and the elapsed
pilot are outstanding until performed after approval.
