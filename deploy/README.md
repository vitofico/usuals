# Deployment package, not yet approved

All files are local review material. Do not apply manifests, import an image,
provision storage/DNS, decrypt secrets or change backup jobs without Vito's
explicit approval. No cluster credentials are required for the local checks.

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
`operator-jobs/` files, the runbook and recovery guide. `bundle.json` records the
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

The package targets the locally observed single-node amd64 MicroK8s cluster,
Traefik `websecure`, cert-manager `letsencrypt-prod`, `microk8s-hostpath` and
`usuals.example.org`. Those names/capabilities require live confirmation.
The relay is a one-replica Recreate deployment, UID10001, read-only rootfs,
dropped capabilities, no service account token, bounded JVM and private metrics.
PostgreSQL16 is dedicated, non-root UID70, with separate app and replication
roles. Its live data uses a 10 GiB local RWO PVC. Receipt identity/fence use a
separate PVC, excluded from database restore. NFS holds only compressed WAL and
verified base backups, under a private UID70 directory provisioned separately.
A claimed NFS size does not enforce a quota. Never reuse another PG archive path.

The [runbook](../docs/sync/runbook.md) gives the ordered deployment, restore,
rollback, deletion and pilot gates. The [recovery-copy format](../docs/sync/recovery-copy.md)
describes the operator grant and offline phone reconciliation boundary.

The final local image/profile evidence is recorded in the release checklist.
`profile-local` builds through the wrapper and runs the relay under 512 MiB and
one CPU with a read-only identity mount. It checks non-root/least-privilege roles,
private metrics, anonymous admission and authenticated near-limit opaque snapshot
writes/reads, followed by successful catch-up. Bootstrap credentials are protected
and removed with disposable state. Results record accepted/shed requests, response
time and sampled memory. This remains a local, single-household stress check;
many-household/long-duration capacity, live network policy and encrypted off-site
recovery remain separate gates.


## Browser identity preparation

The same relay image packages the Usuals browser client, local fonts and locale
catalogs. No separate frontend container or external asset service is required.
Both identity providers remain disabled until explicitly configured. Standalone
accounts and Authentik may be enabled together or separately.

To prepare a configured local-account candidate, add `--local-accounts` to the
existing `--inventory` command. Its first administrator is created later through
the explicit protected `local-admin` operator command, after migration and only
in an approved environment. The bundle never contains an initial password or
activation secret. Account creation/reset does not restore a browser vault or
change encrypted household membership.

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

## Paid hosting boundary

This package prepares the personal/pilot relay. It has no subscription billing,
paid tenant provisioning or hosted admission enforcement. The [paid hosting
plan](../docs/hosted-service-plan.md) records the additional contracts, private
control plane, client checks and commercial operations required before offering
Usuals Cloud. It does not enable paid mode or authorize a deployment.
