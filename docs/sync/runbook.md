# Usuals server runbook

Use an explicit deployment authorization before applying these steps. Record
source commit, image digest, config and operator at each deployment in a private
operator record, never in this repository. Do not put secrets in command
arguments, logs or Git.

The server holds every list of its accounts. Three files live outside the database
and are never restored with it:

| File | What it does | If it is lost |
|---|---|---|
| Identity (`USUALS_IDENTITY_FILE`) | The server's persistent key | Startup fails; never regenerate it silently |
| Fence (`USUALS_FENCE_FILE`) | Marks which database restore is current; also the list feed epoch | Recreate it with `restore-fence`, which signs everyone out |
| List key (`USUALS_LIST_KEY_FILE`) | 32 bytes that encrypt every stored list at rest | Startup fails and no stored list can be opened |

## First deployment

1. Confirm MicroK8s version/admission, the target amd64 node, available RAM/
   disk, hostpath storage, NFS reachability/private UID70 directory, Traefik labels,
   cert-manager issuer and intended DNS/hostname. Confirm HTTP redirects to TLS.
   Verify CNI NetworkPolicy enforcement and Traefik's sanitized/appended forwarding
   behavior. Set `USUALS_TRUSTED_PROXY_CIDRS` to the verified immediate ingress
   peer address range; the checked-in marker fails startup until replaced.
   The server uses only the rightmost X-Forwarded-For address from that trusted
   direct hop and ignores headers from any other peer. Adding a CDN needs review.
   The manifests use common local service conventions; they are not evidence of
   the current live state.
2. Generate private random admin/app/backup passwords and a correct escaped
   `backup-pgpass` file. Fill `deploy/k8s/secrets.example.yaml` privately and
   encrypt with your secret-management process outside Git. Do not apply
   placeholders or send credentials to an assistant. Keep app and replication
   users separate. Bootstrap SQL reads mounted files, not password arguments.
3. Build/test locally, record immutable image/distribution hashes, approve and
   import the image. Set the exact same reference in deployment and both explicit
   jobs. Apply namespace, private secrets, storage, tools and database first.
   Inspect readiness and the least-privilege roles. Initialization runs only on
   a fresh PGDATA; interrupted initialization requires inspection, not blind retry.
4. Run `jobs/initialize-identity.yaml` once. It refuses existing identity and fence
   files. Create the list key on the same protected volume with
   `head -c 32 /dev/urandom`, as a regular file (not a symlink) owned by the server
   user with mode 0600, and point `USUALS_LIST_KEY_FILE` at it. Escrow the identity
   and the list key in encrypted storage separate from database backups. Never
   restore or roll back the fence file with database state.
5. Run `jobs/migrate.yaml`, then start the server, service, network policies and
   ingress. Applied migration checksums and newer schemas fail closed; startup
   never migrates implicitly. A failed initial job must be inspected before
   recreation.
6. Verify TLS/origin, readiness/liveness, no public metrics, actual network denial,
   read-only filesystem, non-root users, PVC durability and restart behavior.
   Create the first administrator with `local-admin` or configure Authentik as in
   [web identity](web-identity.md).
7. Complete off-cluster backup/restore and revocation drills before real data.
   Then run an authorized pass with a physical phone and a browser, and a short
   pilot with two people sharing a list. Add more people only after no unexplained
   loss or duplication.

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

Stored lists are encrypted under the list key, so a database backup is only
useful together with the key it was taken under. Keep the key in escrow for as
long as any backup that needs it is retained, and after a key rotation keep the
old key until the last backup made before the rotation expires. Never store the
key inside the database backup itself: a backup and its key together reveal
every list.

After approval, adapt the scoped Borgmatic template to the installed version
and your encrypted off-cluster repository. The five-minute timer has a
four-minute run deadline. Monitor actual successful archive completion and
recoverable WAL coverage, not just scheduled starts. A successful encrypted
archive followed by `offsite-completed-at` is the completion marker. Page at
10 minutes of stale off-site completion; treat 15 minutes as an RPO breach.
Also alert on archiving errors/lag >2 minutes, base age >8 days, backup-job failure,
PG/live/NFS disk >70% warning/>85% critical and missing identity or list-key escrow.
Do not claim the target until a post-base synthetic commit is recovered from
the encrypted off-cluster archive and its measured age is below 15 minutes.
Borg credentials and encrypted repository configuration are operator-owned.

Pruning is off by default. Setting `USUALS_KEEP_BASES` to N on the base-backup
CronJob turns it on: after each verified base, `prune-backups` re-verifies the
newest N bases, then removes older bases and the archived WAL before the oldest
kept base's start segment (`pg_archivecleanup`). Any failure removes nothing.
Bases taken by hand count toward N. Size N to the recovery window you need after
verifying off-site coverage, and test `pg_archivecleanup -n` dry runs before
changing host retention. Do not delete live `pg_wal`. A WAL failure retains live
WAL until disk fills, so disk/archiver alerts are an availability requirement.

## Restore or lost node

1. Stop ingress and server traffic and the server process. Save protected
   incident evidence. Select a verified base and continuous archived WAL from the
   encrypted off-site copy. Restore into an isolated new PG data volume using the
   same PostgreSQL major version. Never mount live PG data over NFS. Do not expose
   recovered PG or HTTP while restoring. Use recovery.signal and a restore_command
   that decompresses the requested archived WAL; select and verify the recovery
   target.
2. Keep the escrowed identity and the list key that matches the restored backup.
   If the identity is missing or does not match, stop: never silently regenerate
   it. If the fence file is gone, `restore-fence` creates a fresh one; never copy
   a fence from the database backup.
3. With traffic still stopped, run `restore-fence` against the restored database.
   It checks the schema and identity, writes a fresh fence file durably, then
   applies it in one database transaction: it revokes every browser session,
   deletes every phone connection and pending sign-in, and clears local passwords
   and unused setup secrets. A crash between those stages is safe while traffic is
   stopped, because startup applies the external fence again before anyone
   signs in.
4. Start the server. It refuses to start if the list key does not open the stored
   lists. Before restoring ingress, check that an old browser cookie and an old
   phone credential are refused.
5. Issue a protected `local-reset` for the intended local administrator; that
   administrator can then reset other local users. Authentik accounts sign in as
   before.

The new fence is also the epoch of every account's change feed, so each client
reads its lists again from the restored server instead of trusting a cursor from
the future. Browsers simply show the restored lists. A phone whose credential is
refused keeps its lists and unsent edits and asks the person to sign in again.
After it does, it uploads any list the restored database does not have and resends
edits it had not sent yet.
Changes the server had already accepted after the backup was taken are lost.

## Rotating the list key

1. Take and verify a fresh database backup. Create the new key file as in first
   deployment step 4 and escrow it next to the old one.
2. Stop server traffic and the server process.
3. Run `list-rewrap OLD_KEY_FILE NEW_KEY_FILE`. It re-encrypts every stored list
   in one transaction; if it fails, nothing changed.
4. Point `USUALS_LIST_KEY_FILE` at the new file and start the server. Startup
   fails if the key does not open the stored lists.
5. Keep the old key while backups taken before the rotation are retained.

`ai-rewrap` rotates the AI wrapping key the same way; see the
[provider guide](../providers.md).

## Upgrade and rollback

Before an upgrade, record image/distribution/config hashes and take a verified
backup. Migrate with stopped server traffic and a single explicit migration job.
For an application-only rollback, stop traffic and run the prior tested image
against the same unchanged schema, identity, fence and list key. Every normal
startup and database CLI path checks the exact supported migration ledger
before fencing or serving authentication; it rejects unknown/changed versions.
PostgreSQL patch upgrades stay within 16 and require a restore rehearsal. A major
upgrade needs its own plan; never start a different major on the old data dir.

Migration 008 removes the household tables and starts the server-held lists. Their
contents were end-to-end encrypted, so the server cannot convert them: phones upload
their own copies after upgrading, and lists that existed only in a browser are
lost. Take and verify a backup immediately before applying it, and provision the
list key first. Rolling back past migration 008 means restoring that backup with
the matching earlier image.

## Operations and deletion

The public port exposes `/healthz` and DB-aware `/readyz`. Private port 9090
exposes aggregate `/metrics` only, with no Service/Ingress/allow rule. Use an
authorized port-forward or explicitly reviewed monitoring rule. Counters include
request errors/duration buckets, active and waiting requests, JDBC pool occupancy,
heap and aggregate database and list storage. No account, list or IP labels and
no list contents. Backups/WAL/off-site freshness and node memory/disk need host
monitoring.

Eight ordinary requests run at a time, without an unbounded wait queue. Building
list responses is limited to two at a time. A change-feed request that is waiting
for a change holds no slot, for at most 25 seconds, with four per account and 256
in total. They are all held by one process, so run a single replica. Four JDBC
connections, 15-second HTTP deadlines, five-second statements and two-second
lock/acquire limits bound work. Netty uses 1 connection, 2 worker and 4 call
threads. Resource requests are starting budgets; local profile evidence does not
replace amd64 node/load monitoring. Alert on sustained 5xx, two-second foreground
latency breaches, JDBC waiters and memory >80% of 768 MiB. Phones sync every
15 minutes in the background and continuously while the app is open.

Deleting a list in the app removes its contents from the live database at once;
its id is kept so it is never reused. There is no account-deletion API: an
administrator disables the account, which signs it out everywhere. Purging an
account's data on request is an operator task in a reviewed transaction with
traffic stopped. Contents in backups expire only through the retention process,
so document the actual maximum. Phones that held a list keep their local copy.

Record the pilot's start/end, devices/build hashes, offline intervals,
Finish/Undo/conflict observations, backup restore and duplication/loss reports.
Physical tests, live CNI/TLS/storage, encrypted off-site recovery and the elapsed
pilot are outstanding until performed after approval.
