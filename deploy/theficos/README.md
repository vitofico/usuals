# Theficos deployment

Initial deployment authorized directly by Vito on 2026-10-18. Service URL:
[usuals.theficos.dedyn.io](https://usuals.theficos.dedyn.io).
This overlay records the live configuration; it contains no credentials. The
current server/web image is built from `0fd2cac`, merged in PR #11 and deployed
with Vito's explicit approval on 2026-10-20. The initial deployment used source `9c3bd57`.
The current upgrade adds local import of copied notes and Markdown lists.

## 2026-10-20 copied notes and Markdown import

[PR #11](https://github.com/vitofico/usuals/pull/11) merged as `a22837b` after all
CI checks passed. Preserved source `0fd2cac4604b724f12748c8b3f8746d03057c6c2` has the
same complete Git tree as that merge. The source-bound image and platform
manifest are `usuals-relay:sha256-928fdbff2c76c760e4a63efa706c13548df7fbe1bb96e743c7e4e4b4531698e9`.
The verified bundle is `/private/tmp/usuals-notes-bundle-0fd2cac`.

Only the relay image and source annotation changed. All other reviewed runtime
settings, AI profiles, network policy, identity and PVC mounts are unchanged.
Generic candidate manifests were not applied. The imported manifest and config
content matched, and the ready pod reports the expected config digest with zero
restarts. Schema 005 and identity, fence and AI-key hashes are unchanged. The
wrapping key remains mode0600 owned by UID/GID10001. No migration was required;
the previous `15c9b2dc...27277` image remains a schema-compatible rollback target.

Before rollout, `usuals-prenotes-20261020` produced an online PostgreSQL base
backup with WAL. The job and independent manifest verification passed. Fresh
encrypted database and existing verified key escrow copies in
`/Users/vito/personal-repos/usuals-operator-backups/20261020-notes/` match the
node checksums. No plaintext credentials were transferred.

Live TLS/readiness, 19 exact packaged browser assets, all five locales, security
headers, both authentication modes, protected anonymous APIs and stable server
identity passed. Text import is local and does not request a model. Functional
Android and browser checks are in the [verification record](../../docs/verification/2026-10-20-notes-import.md).
Node evidence is `~/usuals/deployments/20261020-notes/`; local evidence is
`/private/tmp/usuals-notes-deploy/`. Android uses the automatic signed release
workflow from main.

## 2026-10-20 streamlined settings

[PR #8](https://github.com/vitofico/usuals/pull/8) merged as `3ac479b` after all CI
checks passed. Preserved source `4b81b6724c65fc085af408c4a63f64d6d365ea3c` has the
same complete Git tree as the merge. The source-bound image and platform manifest
are `usuals-relay:sha256-15c9b2dcabe3df32da4ff98c4a9679efb6d6dadd6515c70c8643d95d93327277`.
The verified local bundle is `/private/tmp/usuals-settings-bundle-4b81b67`.

Only the relay image and source annotation changed. The existing AI profiles,
identity, network policy, PVC mounts and all other runtime configuration stayed
identical to the reviewed baseline. Generic candidate-bundle manifests were not
applied. The imported manifest and its config content were verified; the ready
Deployment pod reports that config digest and has zero restarts. Schema 005 and
identity, fence and AI-key hashes are unchanged; the wrapping key remains a
mode0600 regular file owned by UID/GID10001. No migration was needed. The prior
005-capable image `20b790f8...304e7` remains an image-only rollback target.

Before rollout, `usuals-presettings-20261020` created an online PostgreSQL base
backup at `20261020T200957Z`, with streamed WAL and two successful manifest checks.
The live AI key still matches the previously verified escrow, and the encrypted
escrow bytes match their prior checksum. Fresh encrypted database and key copies
in `/Users/vito/personal-repos/usuals-operator-backups/20261020-settings/` match the
node checksums. No plaintext credentials were transferred.

Live HTTPS, readiness, 19 packaged browser assets including all five locales,
security headers, both authentication modes and closed anonymous APIs passed.
The server identity is unchanged. Native and browser functional checks and their
manual visual/camera limits are in the [verification record](../../docs/verification/2026-10-20-streamlined-settings.md).
Private node evidence is `~/usuals/deployments/20261020-settings/`; local evidence
is `/private/tmp/usuals-settings-deploy/`. Android uses the automatic signed
release workflow from main.

## 2026-10-20 server connection and AI gateway

[PR #6](https://github.com/vitofico/usuals/pull/6) merged as `da580bc` after Android,
server, credential-scan and CodeQL checks passed. The deployed source is its
preserved head `72719fb5294755dc4bada760c66f94baa36ce143`; its complete Git tree
was verified identical to the merge. The source-bound image and platform digest
are `usuals-relay:sha256-20b790f8bfc1102bfb5070c24c3b3ac6464451435e688594c39595b040b304e7`.
The verified local bundle is `/private/tmp/usuals-gateway-bundle-72719fb`.

Vito selected the existing Ollama cloud service. The fixed internal destination is
`http://ollama.ollama.svc.cluster.local:11434/v1`, with `gpt-oss:20b-cloud` for text
and `gemma4:31b-cloud` for images. Both clients disclose external processing:
`isLocal` is false; operator-only `allowHttp` permits the internal proxy hop.
There is no general internet egress from the relay. AI remains an explicit
account choice and import action, bounded to 20 attempts per account per UTC day.
The profile uses Ollama's existing cloud connection. Additional personal-provider
destinations need an operator-approved profile and narrowly scoped egress.

`ai-egress.yaml` is part of this overlay. Apply `ollama-ingress.yaml` separately,
with its explicit `ollama` namespace: including it under this Kustomization would
rewrite its namespace to `usuals`. Both rules restrict namespaces, pod labels and
TCP 11434; other Ollama clients and existing identity/database rules are unchanged.

A separate operator pod created the credential wrapping key exclusively as a
UID 10001, mode 0600 regular file on the retained identity PVC. Its SOPS/age escrow
was decrypted only into hashing and matched the live key; plaintext was not
written or transferred. A probe using the final read-only PVC source and mount
confirmed 0600 permissions before migration. Runtime and migration PVC sources
are read-only to prevent local-volume `fsGroup` handling widening permissions.

With relay traffic stopped, `usuals-preai-20261020` created a fresh base backup;
both the job and an independent `pg_verifybackup` passed. The encrypted database
backup and separate wrapping-key escrow were copied to
`/Users/vito/personal-repos/usuals-operator-backups/20261020-server-ai/` and matched
the node's checksums. Existing encrypted identity/service escrow remains separate.
`usuals-migrate-connection-ai` explicitly applied migration 005 before rollout.
The older four-migration image cannot be used for an image-only rollback. Use a
compatible binary for forward recovery or the fenced database restore procedure.

The final runtime exactly matches the reviewed image, four AI settings and
read-only identity PVC source. PostgreSQL configuration, receipt identity and
fence hashes are unchanged. Live checks passed HTTPS/readiness, 14 source-identical
browser assets, security headers, authentication and anonymous API restrictions.
A temporary ordinary account passed activation, full-code phone approval,
native bearer text extraction, personal credential wrapping/reset, the shipped
web text adapter, randomized vision verification, written-list photo extraction,
revocation and logout. The account and its sessions/AI records were removed.
No owner credentials or existing household data were used. The replica remained
ready without restarts after these requests.

Private node evidence is in `~/usuals/deployments/20261020-server-ai/`; local
evidence is in `/private/tmp/usuals-gateway-deploy/`. Longer household/load pilots,
physical camera scanning and recurring off-cluster backup monitoring remain the
separate operational gates below. This rollout does not claim they are complete.

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
  (UID70 for PostgreSQL/backups, UID 10001 for identity). Capacity declarations
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
  5432. Relay general internet egress was denied. UID 10001, read-only application
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
service, billing or paid tenant provisioning was enabled. Android publication
follows the automatic release workflow.
See the [runbook](../../docs/sync/runbook.md) for restore fencing and pilot gates.
