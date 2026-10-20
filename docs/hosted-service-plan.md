# Usuals paid hosting preparation

2026-10-18. Prepared locally at Vito's request. This is a proposed implementation
plan for **Usuals Cloud**, a paid managed service using the existing encrypted
relay. Billing, public registration, customer provisioning and hosted access
controls described here are not implemented or enabled. The existing deployment
candidate is for the personal/pilot relay, not a commercial launch.

This hosting preparation does not authorize payment-provider configuration,
DNS changes or cluster operations. The maintainer separately approved private
source publication on 2026-10-18; it does not enable a paid service. Ask before
any cluster deployment.

## Offer and boundaries

The recommended first offer is one subscription per household, with monthly and
annual billing. The payer funds hosting for the household's members. Prices,
member/device/storage allowances, trial terms and retention periods remain
operator decisions after capacity and cost validation. Do not advertise unlimited
history or an availability guarantee against the current single-node deployment.
Start with an invitation-only pilot; public signup is a later acceptance gate.

| Edition | What it provides | Account or payment |
| --- | --- | --- |
| Local Usuals | Existing Android capture, history, smart suggestions, recipes, barcodes, import/export and offline use | Neither required |
| Self-hosted Usuals | Existing relay, encrypted sharing, browser client, local accounts and optional OIDC | Operator controls access; no commercial dependency |
| Usuals Cloud, proposed | Managed relay, browser access, backups, upgrades and support | Hosting subscription; household membership remains separately approved |

The service sells hosting and operations. Shopping memory remains on the phone.
Open Food Facts reads and recipe fetching retain their client-origin boundary.
ADR-0014 permits optional server-routed model calls on explicit text/photo inputs.
This does not add model credits, a product-data proxy, paid barcode access or an
operator copy of encrypted shopping plaintext.

The current Apache-2.0 core license stays unchanged. Keep proprietary service
code, prices, payment integrations and customer operations in a separate private
`usuals-cloud` repository. This repository can expose generic, reviewed service
admission contracts that remain useful to self-hosted operators. Do not copy
private code from another cloud product into this repository.

## Existing implementation and gaps

The [relay](../server/README.md) already has authenticated devices, encrypted
households, bounded ciphertext storage and operations, durable rate limits,
explicit identity initialization and a restore fence. The [browser identity
layer](sync/web-identity.md) supports standalone accounts and Authentik with
closed public registration, account administration and browser session binding.
The [deployment package](../deploy/README.md) has local preparation, integrity
checks, backup/restore tooling and hardened containers.

Those mechanisms do not constitute paid tenancy:

- Native Android devices can authenticate without a browser account. A browser
  checkout button cannot enforce a household subscription.
- The operator bootstrap token is a protected one-use relay capability, not a
  customer provisioning API. Never expose it through a checkout return URL.
- A browser account, its enabled flag and its administrator role are separate
  from household ownership and encryption keys.
- Current storage and concurrency limits are technical safety limits, not a
  tested commercial allowance or a fairness guarantee between customers.
- The current near-limit profile reached 499.3 MiB in a 512 MiB container for one
  household with accounts disabled. Account-enabled load, many households,
  sustained sockets and adequate headroom remain sizing gates.
- History has no event/tombstone compaction. Capacity planning must include its
  growth, snapshots, retained backups and security rotations.

## Small service composition

Reuse Kotlin/Ktor and PostgreSQL for the relay. Recommend a small separate cloud
control plane with its own database/role, a durable PostgreSQL work queue and a
worker. Avoid adding Redis, Kafka or a second identity system for this offer.
Confirm the control-plane implementation stack and payment provider against the
Quire Cloud reference before starting that private implementation.

Route `/cloud/` to the control plane under the same approved HTTPS origin as the
existing browser client and account APIs. Use versioned HTTP contracts, not core
SQL access or imports of relay internals. The cloud service must reauthorize
through core identity, with exact application-cookie forwarding to one fixed
internal origin. It must not receive IdP tokens or duplicate password storage.
Do not expose core administrator sessions to ordinary billing handlers.

First-party cloud assets join the browser's existing trust boundary: scripts on
the editor origin can observe unlocked browser keys. Keep payment scripts out of
that origin. Use a full-page redirect to provider-hosted checkout and account
billing pages; do not embed a payment SDK in the encrypted editor. Keep all
cloud copy in complete en/es/it/fr/de catalogs, with resources-only locale addition.

## Payer and household binding

Create a service-generated tenant ID and associate it with the immutable Usuals
account ID. Payment customer IDs and email addresses never establish ownership.
One tenant sponsors one exact `(serverId, canonical origin, householdId)` in the
first version. Members share that hosting entitlement; they do not each need to
subscribe. Paying alone grants no membership, list keys or recovery authority.

The association needs both an authenticated payer and the current household
owner's signed approval. Define a new domain-separated proof including the
payer account ID, tenant ID, server ID, canonical origin, household ID, nonce,
expiry and action. Verify current owner authority and consume the nonce
atomically under the household lock. Bind the request to the initiating account
session and protect browser mutations with CSRF. Never reuse a generic login
proof or accept a household/customer ID supplied by a checkout success page.

New hosted household creation needs a scoped, one-use provisioning ticket bound
to the tenant and the owner's device public key. The device generates household
keys. Provisioning must be idempotent across worker restart, payment retry and
lost responses, and must not admit an unpaid or unrelated household. Existing
self-hosted enrollment continues to work without a commercial service.

Changing the sponsor requires explicit account consent and fresh owner approval.
Ownership rotation does not implicitly change the bill payer. Account disable,
payment cancellation, membership removal and household deletion are distinct
operations. A bill payer or cloud administrator cannot replace an owner, recover
a lost vault or clear a restore fence.

## Generic relay admission contract

The core integration is an explicit optional admission policy, not payment logic
inside `:smart`, `:enrich`, Room or the operation reducer. Its default preserves
self-hosting. Hosted mode must fail startup if its required verifier/configuration
is absent; it must never fall back to the self-hosted policy after an error.

A proposed signed grant contains a schema version, issuer/key ID, target server
and origin, tenant and household IDs, generation, issue/expiry times, admitted
resource limits and service access mode. Plan names and payment identifiers stay
in the cloud service. Use a separate service signing identity, pinned verification
keys, durable generation anti-rollback and explicit key rotation. Do not reuse
receipt keys, owner signatures, IdP tokens or checkout URLs as grants.

Grant installation and enforcement must use the same household serialization as
relay writes. Durable per-tenant reservations/counters must prevent concurrent
or multi-replica quota overshoot. A grant cannot authorize a request that normal
membership, account checks, the restore fence or technical limits reject. There
is no synchronous payment-provider request in an append transaction.

| Operation | Active service | Uploads paused or grant refresh unavailable |
| --- | --- | --- |
| Local Android editing and local export | Existing behavior | Existing behavior; preserve exact pending operations |
| Authenticated catch-up, snapshots and committed receipts | Allowed within security/safety limits | Remain available during the agreed data-access period |
| Ordinary new operation uploads | Allowed within tenant and technical limits | Pause with a structured retryable service reason; allocate no sequence or receipt |
| Exact retry of a committed upload | Return its original receipt | Still return the durable receipt; do not charge storage twice |
| New household/list capacity | Requires a valid reservation/grant | Denied without changing membership or consuming a ticket |
| Revocation, owner recovery and necessary key rotation | Existing signed authority required | Preserve a bounded security-maintenance path |
| Billing account pages and cancellation | Exact account authorization | Remain reachable independently of list upload admission |

Classify snapshot and membership maintenance explicitly. Since snapshot contents
are encrypted, the server cannot prove they contain no edits. Commercial policy
must bound maintenance bytes/rates and reserve space for rotations; it must not
promise cryptographically read-only plaintext. User copy should say hosting
uploads are paused, while local editing continues. Do not deny all POST requests
at a proxy: that can block authentication, recovery or durable retry resolution.

Android and browser clients must recognize the negotiated service reason, retain
the exact encrypted outbox, allow reads and resume without duplicates. Closing
sockets must not turn a billing decision into account revocation or vault loss.
New admission/error contracts need shared fixtures and independent review before
any enforcement is enabled. Preserve the existing wire/version guarantees.

## Billing and reconciliation

Stripe Checkout/Billing and the hosted customer portal are the initial candidate,
subject to the Quire Cloud comparison and Vito's commercial choice. No provider
account, secret, product, price or webhook is created by this plan. The portal
can handle payment methods, invoices and cancellation.
[Provider reference](https://docs.stripe.com/customer-management).

Create checkout/portal sessions on the server for the authenticated tenant only.
Accept an allowlisted product/interval key, never an arbitrary price, amount,
customer ID or return URL from the browser. Pin the provider API version, separate
test/live IDs and credentials, and use idempotency for outbound requests.
A checkout redirect only displays progress; verified provider state grants access.

Verify webhook signatures against bounded raw bytes with an injected clock and
support secret rotation. Persist the provider account, event ID, payload digest
and work item before acknowledging delivery. Reject the same event ID with a
different digest. Serialize reconciliation per subscription/tenant, retrieve
canonical provider state and apply an atomic local transition with a new grant
generation. Deduplicate retries and run periodic repair for missed deliveries.
Stripe does not guarantee event order, and different events can have the same
creation timestamp. A timestamp-only watermark is insufficient.
[Webhook reference](https://docs.stripe.com/webhooks).

| Proposed service state | Effect |
| --- | --- |
| Awaiting initial payment/provisioning | Billing/account access only; no unscoped relay bootstrap |
| Active | Normal admission under the household grant |
| Renewal payment retry | Explicit bounded grace under the agreed offer; notify the payer |
| Grace ended or subscription ended | Pause ordinary uploads; retain authenticated reads/export and security maintenance for the agreed access period |
| Provider temporarily unavailable | Honor the last valid grant until expiry; then pause new uploads while preserving retained access |
| Explicit security suspension | Apply existing security controls separately from billing |
| Retention deadline reached | A separately approved deletion workflow with notices, audit and restore-safe purge; no automatic purge in the first implementation |

Distinguish initial unpaid, renewal failure, action required, cancellation at
period end, immediate cancellation, refund, dispute, provider pause and successful
recovery. Confirm which provider events/state transitions matter rather than
mapping every cancellation or invoice notification to the same action.
[Subscription reference](https://docs.stripe.com/billing/subscriptions/webhooks).

Restores must invalidate transient provisioning/grants, reconcile subscription
state and preserve the relay's irreversible frozen-household rules. Payment can
resume only a legitimately retained household; it cannot unfreeze restored IDs.
Changing service providers requires an explicit new-household/key migration or
export path, never rewriting a URL in signed stored operations. Verify the full
exit and re-enrollment flow before selling hosted access.

## Operations and release gates

Start with a private pilot, exact-origin TLS, explicit staff admission and
existing closed registration. Public onboarding requires its own abuse controls,
contact verification/recovery policy, signup quotas and rate-limit fairness.
Standalone accounts and Authentik remain supported; there is no first-customer
administrator promotion, email-based linking or automatic cluster staff access.

Before selling, choose and document prices, taxes, currencies, territories,
refund/cancellation terms, support scope, service targets and data-access/retention
periods. Measure account-enabled many-household capacity, cost per active
household, backup/off-site restore and failure recovery. Do not present the
existing single-household smoke test as commercial capacity evidence.

Plan Android distribution before adding purchase links or billing SDKs. Google
Play's payment and external-offer rules depend on the channel, region and enrolled
program. Keep Android purchase entry points out of this preparation until that
decision is reviewed against the applicable policy.
[Policy reference](https://support.google.com/googleplay/android-developer/answer/9858738).

Use separate nonsecret hosted inventories and secret-file references for the
cloud database, service signing identity and provider credentials. Paid mode,
public registration and live billing stay disabled by default. Extend offline
bundle verification to reject mismatched test/live configuration, unbound service
origins, missing admission verifiers, unsigned grants and incomplete retention
configuration. Never put customer data, live price IDs, credentials
or a deployment approval into example inventories.

## Ordered work and done conditions

| Phase | Deliverable | Done when |
| --- | --- | --- |
| C0, preparation | This plan and private offer worksheet | Boundaries/gaps, the missing Quire Cloud reference and pending operator decisions are recorded; no live service is implied |
| C1, contract | Generic admission, owner binding/provisioning proofs and client reason/capability contract | Shared JVM/browser fixtures cover replay, wrong tenant/origin/owner, expiry, generation rollback, exact retry, interrupted provisioning and bypass by unbound native devices; independent review closes findings |
| C2, private cloud spine | Separate repository, core account adapter, tenant store, durable worker and fake provider | Two synthetic customers remain isolated; CSRF, cookie forwarding, reservations and restart/idempotency checks pass; no copied identity database or private code in core |
| C3, relay and clients | Explicit hosted admission and translated service state | Native and browser cannot bypass limits; expiry/race/restart tests pass; uploads pause without losing edits; reads, receipts, removals and recovery pass on emulators and browser; self-hosted/offline regressions stay green |
| C4, provider sandbox | Checkout, portal, signed inbox and canonical reconciliation | Provider test mode covers duplicate/equal-time/reversed events, failures, cancellation/refund/dispute and missed-event repair; no live charge or real customer is used |
| C5, commercial operations | Hosted inventory, capacity/cost results, restore/deletion drills and service documents | Many-household/account-enabled measurements and security review meet chosen targets; prices/retention/store-channel rules are approved; customer data exit works |
| C6, approved pilot | Candidate from clean commits and separately approved cluster/IdP/payment setup | Deployment approved explicitly, synthetic sandbox pilot passes, then the limited paid pilot is authorized separately |

C1 through C6 are not completed by this preparation. Reuse the existing Docker
wrapper for core/server builds, run full required suites in the background,
verify client changes on emulators/browser and commit locally. Keep the phone
untouched unless Vito explicitly authorizes its use again.
