# ADR-0013: encrypted shared lists through an ordered relay

- **Status:** Superseded (2026-10-21) by [ADR 0017](ADR-0017-server-held-lists.md); accepted for local implementation, 2026-10-16
- **Relates to:** ADR-0009, ADR-0010, ADR-0012

## Context

The implemented shopping list is single-phone and schema 4. Vito authorized
the shared-list plan starting with S0, while retaining explicit approval before
cluster deployment. Existing Quire, Spunto and cluster files establish the
MicroK8s, Traefik, cert-manager, Kustomize and local-image-import conventions.

Offline edits, Finish and Undo need precise convergence rules. List contents
must remain hidden from the relay; model, recipe and catalog traffic continues
to originate on the phone. Existing installed data cannot be destructively
migrated to add sharing.

## Decision

Adopt the [S0 contract](../sync/README.md) and its synthetic fixtures as the
implementation target. Use named lists scoped to a household, an atomic local
outbox and an ordered encrypted operation log with stable intent ids. Clients
reduce operations using explicit revisions; physical clocks do not choose
conflict winners. Item-derived effective purchases remain unique within a list.

Use the pinned Tink primitives for authenticated encryption, signed envelopes
and recipient key packages. Verify invitation keys independently; use signed
membership transitions for enrolment, list creation, revocation and recovery.
Every transition rotates affected keys and binds exact snapshot cutoffs. A
registered recovery principal has a restricted session rather than member
privileges. Restored server households remain fenced; explicit recovery copies
use fresh household identities and keys so stale rosters cannot revive devices.

Keep the relay in an isolated Kotlin/Ktor JVM build with PostgreSQL and bounded
JDBC access. Its compiler/dependencies do not upgrade Android incidentally.
Use the existing cluster conventions later, with immutable images, distinct
credentials and measured backup/restore evidence. No third-party read proxy,
FCM, analytics, Redis or queue broker is introduced.

## Consequences

S0 contains contracts, migration/archive design, reviewed security boundaries
and test-only primitive probes. It does not add a running relay, schema 5,
production sync wiring or a multi-phone claim to the current app. A separate
read-only review found seven gaps; all were fixed and re-reviewed in the
review record (`docs/sync/review.md`, removed with ADR 0017 and kept in Git history).

S1 must implement and test the migration and reducer against the fixtures.
S2 to S4 must verify real authorization, persistence, encrypted exchange and
two-device behavior. S5 requires deployment approval and operational drills.
Encrypted history is retained without log compaction in v1. Recovery favors
preserving revocation over automatically resuming a restored household.

## S1 amendment: explicit repeat cycles

A swiped repeat must remain deferred until the next effective shop without
resurrecting an already consumed occurrence. Added a new cycle bound to its
accepted deletion and first subsequent effective Finish. Durable cycle heads
and immutable base hashes prevent cancelled-ancestor and cross-identity bypass.
The independent follow-up review closed both findings; nine added reducer
scenarios cover the amendment. Crypto and membership profiles are unchanged.
