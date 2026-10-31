# Deployment packages

The generic templates remain review material and require deployment approval.
No cluster credentials are required for the local checks below.

```sh
server/scripts/test-local
server/scripts/check-manifests
server/scripts/drill-local
server/scripts/profile-local
```

`check-manifests` renders Kustomize locally and checks pod security, bounded
resources, private metrics and absence of rendered secrets. It uses Kustomize,
Python 3 and Ruby's standard YAML parser. It does not claim API schema/admission,
CNI enforcement, storage or TLS verification. Explicit identity/migration jobs
are checked separately and deliberately excluded from automatic reconciliation.

After committing the verified source, prepare a private bundle outside the
repository:

```sh
server/scripts/prepare-deployment --output /private/tmp/usuals-deploy-candidate
server/scripts/prepare-deployment --verify /private/tmp/usuals-deploy-candidate
```

Preparation builds the server distribution through `scripts/dgradle`, validates
the base manifests locally, builds the amd64 relay and obtains the pinned amd64
PostgreSQL image. It saves both image archives, rendered `manifests.json`, separate
`operator-jobs/` files, the runbook. `bundle.json` records the
clean source commit/branch, Docker image IDs and platform manifest digests,
distribution hashes and every bundle file hash. It rejects source changes during preparation, an existing output
folder, dirty source and optimized Python that would disable validation. It never
imports images into the cluster, pushes a registry tag or applies anything.

The default is a **candidate**: the unverified ingress-proxy marker remains and
relay startup fails closed. To render a configured candidate, copy
`inventory.example.json` outside the repository, replace the proxy marker with
verified immediate ingress peer CIDRs, and confirm every other observed value:

```sh
server/scripts/prepare-deployment --inventory /private/tmp/usuals-inventory.json --output /private/tmp/usuals-deploy-configured
```

The inventory accepts only nonsecret infrastructure fields. Namespace, selector,
CIDR, hostname and path validation is local; it does not prove live labels, CNI,
TLS, NFS access, admission or available capacity. Secrets remain separate operator
steps. Both candidate and configured bundles say `deploymentApproved: false`.
Verification checks local integrity, not approval or an external signature.
The source templates keep their deliberately invalid deployment markers.

Image names are content-derived tags such as `usuals-relay:sha256-<image-id>`.
Docker image IDs depend on the local image store; the bundle also records the
selected platform manifest digest. Both archives must be imported and their actual IDs checked only after approval. Identity initialization
and migration are separate explicit jobs, never part of automatic reconciliation.
Follow the runbook order; never initialize an existing identity again.

The package targets a single-node amd64 MicroK8s cluster with Traefik
`websecure`, cert-manager `letsencrypt-prod` and `microk8s-hostpath`. Replace
`usuals.example.org`, the issuer and the NFS server in the templates and
inventory examples with your own values, and confirm them against the cluster.
The relay is a one-replica Recreate deployment, UID10001, read-only rootfs,
dropped capabilities, no service account token, bounded JVM and private metrics.
PostgreSQL16 is dedicated, non-root UID70, with separate app and replication
roles. Its live data uses a 10 GiB local RWO PVC. Receipt identity/fence use a
separate PVC, excluded from database restore. NFS holds only compressed WAL and
verified base backups, under a private UID70 directory provisioned separately.
A claimed NFS size does not enforce a quota. Never reuse another PG archive path.

The [runbook](../docs/sync/runbook.md) gives the ordered deployment, restore,
rollback and deletion steps.

## List key

Lists are stored encrypted with AES-256-GCM under a 32-byte key file named by
`USUALS_LIST_KEY_FILE` (`/identity/list.key` in `k8s/relay.yaml`). The relay
refuses to start without it, or when it cannot open the stored lists. Create it
once on the identity volume before the first start of a release with migration
008: 32 random bytes (`head -c 32 /dev/urandom`), a regular file owned by UID
10001 with mode 0600. Escrow an encrypted copy separately from the database
backups, for as long as any backup needs it: a backup stored next to its key
reveals every list, a backup without it is unreadable, and losing the key loses
every list. To rotate it, stop traffic and run `list-rewrap <old key file> <new key file>`.

Migration 008 replaces the household tables with server-held lists and drops
them. Take and verify a database backup first: rolling back past 008 means
restoring that backup.

The final local image/profile evidence is recorded in the release checklist.
`profile-local` builds through the wrapper and runs the relay under 512 MiB and
one CPU with a read-only identity mount. It checks non-root/least-privilege roles,
private metrics, anonymous admission and authenticated near-limit list uploads,
followed by successful catch-up reads. Results record accepted/shed requests,
response time and sampled memory. Its peak sits close to 512 MiB, so
`k8s/relay.yaml` allows 768 MiB. This remains a local, single-server stress check;
long-duration capacity, live network policy and encrypted off-site recovery
remain separate gates.


## Browser identity preparation

The same relay image packages the Usuals browser client, local fonts and locale
catalogs. No separate frontend container or external asset service is required.
Both identity providers remain disabled until explicitly configured. Standalone
accounts and Authentik may be enabled together or separately.

To prepare a configured local-account candidate, add `--local-accounts` to the
existing `--inventory` command. Its first administrator is created later through
the explicit protected `local-admin` operator command, after migration and only
in an approved environment. The bundle never contains an initial password or
activation secret.

For Authentik, copy `identity-inventory.example.json` to a private local path and
replace every marker with verified nonsecret values. Use the exact per-provider
issuer, client ID, immutable admin subjects, allowed groups and actual ingress
TLS destination addresses as /32 or /128 egress hosts. Then:

```sh
server/scripts/prepare-deployment --inventory /private/tmp/usuals-inventory.json --oidc-inventory /private/tmp/usuals-identity.json --local-accounts --output /private/tmp/usuals-deploy-configured
```

The renderer adds the provider settings, a read-only `usuals-oidc` Secret
reference with key `client-secret` via a regular-file subPath mount, and a TLS443
egress policy restricted to those addresses. It renders no Secret data and never
creates the Authentik provider. This policy needs live CNI/ingress confirmation;
pre/post-DNAT enforcement varies and local rendering cannot prove connectivity.
Keep the default no-general-internet-egress policy. Local-only accounts add no
IdP egress or OAuth secret mount.

The [identity guide](../docs/sync/web-identity.md) records provider creation,
callback/session and account-administration requirements. Suppress callback query
logging at ingress: one-use authorization codes necessarily arrive at
`/auth/callback`. The candidate contains this guide and the browser security
boundary. Actual provider setup, secrets, network rules and deployment still
require approval. The prior candidate at source6780245 is historical and does
not contain browser or account support.

## Client feature compatibility

Ship compatible server support before using occasion packs with account lists.
`/v1/instance` advertises `occasionPacks: true`; clients treat a missing field as
unsupported. Android checks before queuing additions and caches verified support
for offline use; the browser checks each use. Templates remain on their client,
scoped to account and canonical server on Android, and account and list in the
browser. Confirmed additions use the existing shared-list synchronization.
See [occasion packs](../docs/occasion-packs.md) and the
[list wire contract](../docs/sync/lists-v2.md).

Quiet shared-list notifications depend on the server's transactional activity
feed as well as client opt-in. Provider choices require the optional gateway
configuration described in [provider setup](../docs/providers.md). These features
do not change the deployment, backup or explicit operator approval requirements.

## Paid hosting boundary

This package prepares the personal/pilot relay. It has no subscription billing,
paid tenant provisioning or hosted admission enforcement. The [paid hosting
plan](../docs/hosted-service-plan.md) records the additional contracts, private
control plane, client checks and commercial operations required before offering
Usuals Cloud. It does not enable paid mode or authorize a deployment.
