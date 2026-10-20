# Theficos deployment

Initial deployment authorized directly by Vito on 2026-10-18. Service URL:
[usuals.theficos.dedyn.io](https://usuals.theficos.dedyn.io).
This overlay records the live configuration; it contains no credentials. The
current server/web image is built from `a16ca3c`, deployed with Vito's explicit
approval on 2026-10-20. The initial deployment used source `9c3bd57`.
No application source was changed for either deployment.

## 2026-10-20 upgrade

Published source commits through `9729d7c` to `main` and `feat/smart-list` using
normal fast-forwards. Runtime source is `a16ca3c9be200a69cff49f1b3010257aa18d9a22`;
the following F-Droid commit changes only documentation, metadata and its tests.
The new relay image and amd64 platform manifest are:

`usuals-relay:sha256-f09e84003cdaa689bea6928a090231ba6d722b8405c64284976358bf1a44748e`.

The source-bound bundle at `/private/tmp/usuals-import-deploy-a16ca3c` passed
integrity verification. Its archive SHA256 was checked again on the node before
import through the group-authorized containerd socket. The actual runtime
manifest digest matched, and the image patch/migration Job passed API dry-run.
Only the existing relay image and source annotation changed. Live comparison
confirmed its Authentik, local accounts, resources, secret mounts and storage
configuration were preserved. The generic candidate manifests were not applied.

With the relay stopped, `usuals-preupgrade-20261020` created a fresh base backup.
Both the job and an independent `pg_verifybackup` succeeded. Base backups and WAL
were streamed directly into SOPS encryption with the existing age recipient.
The checksum-verified encrypted archive, existing identity escrow and service
credentials are retained in the previously approved private destination
`/Users/vito/personal-repos/usuals-operator-backups/20261020/`. No plaintext was
transferred or decrypted. This does not complete the separate restore drill or
configure recurring off-cluster backups.

Explicit job `usuals-migrate-a16ca3c` applied migration 004 before rollout. The
relay is ready, and identity/fence file hashes are unchanged. This migration adds
web household ownership. The old `9c3bd57` binary rejects a four-entry migration
ledger, so it is not an application-only rollback target after this upgrade.
Use a tested four-migration-compatible image for forward recovery; database
restoration requires the runbook's fencing and reconciliation procedure.

The live version includes web-first household creation without an invitation,
phone QR pairing and Markdown/photo import. Browser image-model origins remain
empty; enabling a suitable provider still requires intentional configuration.
Photos are read directly by the configured provider after explicit verification
and consent, never through the relay.

Validation reused the unchanged runtime's completed Docker build/test gates:
904 Android cases, zero failures/errors, five intentional live skips; 22 release
safety checks; Android lint; 140 server/core JVM and 139 Node tests; native/browser
crypto interoperability; emulator import scenarios and independent source review.
Fresh deployment checks passed TLS/readiness, ten byte-identical packaged source
assets, no-store/security headers, both auth providers, closed anonymous account
and household APIs, private metrics, and an empty model-origin allowlist.
Desktop and 16 KB emulator Chrome render the deployed sign-in page. Authentik
reaches its normal owner login form. Authenticated creation, QR and import flows
passed against disposable local fixtures; an authenticated production pass still
requires the owner's normal sign-in. No owner credentials or phone were used.
The published Android APK and paused F-Droid MR are unchanged.

Private evidence is in `/private/tmp/usuals-upgrade-20261020/` and
`~/usuals/deployments/20261020-a16ca3c/` on the node. The original integrity-bound
bundle retains its preparation-time `deploymentApproved: false`; this record
documents the subsequent explicit approval and successful deployment.

## Observed infrastructure and configuration

- Single amd64 node `theficos`, MicroK8s Kubernetes 1.36.2, Calico 3.29.3.
- Traefik 3.7.14, namespace `traefik`, label `app.kubernetes.io/name=traefik`.
  The immediate ingress pod uses the node's allocated `10.1.41.64/26` and
  `10.1.41.128/26` Calico blocks. Only Traefik-selected pods may enter port 8080.
  Live static configuration has no trusted client forwarding overrides.
- Let's Encrypt `letsencrypt-prod` HTTP01 certificate, valid public HTTPS and
  HTTP 308 redirect. deSEC CNAME `usuals` points to `auth.theficos.dedyn.io.`;
  the existing dynamic DNS updater already maintains that target. No duplicate
  updater or plaintext DNS credential was added.
- Dedicated namespace, database roles and independently retained identity PVC.
  Three static local PVs use `/mnt/cluster-data/usuals/{postgres,identity,backups}`
  on the 3.7 TiB data disk. All use `Retain`, node affinity and private ownership
  (UID70 for PostgreSQL/backups, UID10001 for identity). Capacity declarations
  do not enforce filesystem quotas. No NFS export or other service changed.
- PostgreSQL requests 150m CPU/512Mi; relay requests 100m/384Mi and is limited to
  one CPU/768Mi. Backup jobs request 50m. Existing reservations prevented the
  original 500m database request from scheduling. No other workload was resized.
  The relay keeps its original bounded JVM heap and one-replica Recreate policy.
- Local accounts enabled, public registration closed. Local administrator
  `vito` awaits protected activation and a password chosen by the owner.
- Dedicated Authentik confidential provider `usuals-web`, per-provider issuer
  `/application/o/usuals/`, strict `/auth/callback`, RS256 signing, UUID subjects
  and signed admission groups. Only `homelab-users` or `homelab-admins` may use
  the app. Group membership grants ordinary access; the local account controls
  Usuals administration. No existing user or provider was changed.
- The IdP hostname resolves inside the relay to the stable ingress LAN address
  `192.168.1.210`. TLS still validates the exact public hostname. Egress permits
  only the LAN ingress/selected Traefik pods on 443, plus required database/DNS.
  A changing WAN address does not require weakening the OIDC egress policy.
- Usuals' ingress access logs are disabled to protect callback authorization
  codes, using Traefik's [documented router annotation](https://doc.traefik.io/traefik/reference/routing-configuration/kubernetes/ingress/).
  General internet egress and public metrics remain denied.

## Initial deployment artifacts and operator state

Relay platform manifest:
`sha256:ae12c393fe7fe1f26017aac8c8b28d587dd17d7fd476e5345c27e73f9b9deae6`.
PostgreSQL platform manifest:
`sha256:1a66d744c1b459e13b05a8fca341da84cb63383e99ce262210efee5a319d4551`.
Both archives were imported with the node's MicroK8s group-authorized containerd
socket and actual runtime references verified. Images use `imagePullPolicy: Never`.

The immutable prepared bundle is `/private/tmp/usuals-deploy-9c3bd57` locally.
The node has `~/usuals/deployments/20261018-9c3bd57/bundle`, its checked file hashes,
separate one-time operator jobs and applied configuration snapshots. The bundle's
`deploymentApproved: false` remains an integrity-preserved preparation statement;
the later user authorization is recorded here, not by rewriting the bundle.

Secrets were generated on the node and applied with server-side apply, without
secret-valued last-applied annotations. SOPS encrypted recovery files reside in
`~/usuals/private/`, mode 0600 in a mode 0700 directory. The signing-identity
escrow excludes the restore fence. Its live file is independent of database
backups and must never be rolled back with them.

Local administrator setup is in `admin-activation.enc.json`. On the node, the
owner can decrypt it to a protected terminal/file with their existing age key,
then use **Activate or reset account** and choose their password. The one-use
setup expires after 24 hours. Regenerate an expired setup through the documented
`local-reset` operator command, without changing the account's role or identity.
An account sign-in does not create or decrypt a shared household.

The first Android household requires an explicit one-use bootstrap capability.
On the node, obtain it only when the owner is ready, with protected redirection:

```sh
umask 077
microk8s kubectl -n usuals exec deploy/usuals-relay -- \
  /opt/usuals/bin/usuals-relay bootstrap-token > ~/usuals/private/household-bootstrap.txt
```

Deliver it privately to the owner. Never put bootstrap/activation secrets in
URLs, screenshots, job logs or Git. Run identity initialization only once;
`usuals-initialize-identity` and `usuals-migrate` completed successfully already.

## Verification

- Docker `server/scripts/test-local` passed, including PostgreSQL integration,
  JVM/browser crypto interoperability and browser tests. It uses `scripts/dgradle`.
  The current Android APKs and emulator had passed the complete theme release
  gate immediately before deployment; no Android source changed here.
- Base and overlay Kustomize rendering passed; the overlay's 20 resources match
  the deployed configuration and pass live API dry-run. No Secret or initialization
  Job is included in reconciliation. Do not reapply the generic candidate over it.
- TLS verification, HTTP redirect, readiness, locked browser UI, CSP, closed
  `/api/me`, denied `/metrics`, local-auth configuration, Authentik discovery,
  strict PKCE redirect and Secure/HttpOnly binding cookie passed. Invalid callback
  proof returns 401. A harmless callback probe was absent from ingress access logs.
- Real desktop browser and emulator Chrome show the deployed mobile/desktop
  page with both sign-in options. Existing native emulator data and phone remain
  untouched. Full sign-in with the owner's credentials is still an owner action.
- An untrusted temporary pod could not reach relay 8080, metrics 9090 or database
  5432. Relay general internet egress was denied. UID10001, read-only application
  filesystem and read-only identity/fence mounts were confirmed. Temporary storage
  and network-probe Jobs were removed after verification.
- Database and relay restarts completed; identity/fence hashes stayed identical.
  The 24-table database and least-privilege application/replication roles persist.
- First and final base backup Jobs completed with `pg_verifybackup`; WAL archiving
  reported three successes and zero failures at the first check. Weekly base
  backup CronJob is enabled; WAL archive timeout is 60 seconds. No pruning enabled.

Private evidence is in `/private/tmp/usuals-live-deploy/` locally and the node's
deployment directory. The overlay can be rendered without cluster access:

```sh
kustomize build deploy/theficos
```

For live inspection use SSH and `microk8s kubectl` on the node, following the
cluster repository's conventions. Do not use an unrelated local kubectl context.

## Application icon

Authentik's Usuals application references
`https://usuals.theficos.dedyn.io/assets/mark.svg`, the canonical aubergine and
champagne dotted U. On 2026-10-19 the application metadata's `meta_icon` was
updated and the served SVG verified. No access policy, user, provider or
deployment changed. Keep this reference aligned with `web/assets/mark.svg`.

## Remaining operational gates

On 2026-10-19 Vito explicitly approved copying encrypted recovery material to
`/Users/vito/personal-repos/usuals-operator-backups`. The `20261019/` snapshot
contains the signing identity, service credentials, base/WAL database archive and
initial administrator activation. All four encrypted files match the node's
checksum manifest and use the existing age recipient. Local permissions are
0700 for directories and 0600 for files; nothing was decrypted. A local
`SHA256SUMS`, recovery note and `backup-info.json` record verification.

This preserves the initial 2026-10-18 snapshot outside the cluster. The existing
Borg repository is node-local; no recurring off-cluster schedule or 15-minute
RPO/two-hour RTO is claimed. Complete an isolated off-cluster restore drill and
backup monitoring before entrusting the service with real shared household data.

Owner sign-in/activation, the physical-device sharing pass, seven-day two-person
pilot and sustained account-enabled capacity remain open. No commercial hosted
service, billing, paid tenant provisioning or public APK release was enabled.
See the [runbook](../../docs/sync/runbook.md) for restore fencing and pilot gates.
