# Shared lists protocol and implementation

S0 through S3 are complete locally, 2026-10-16. S4 shopping transport is
implemented and verified on two emulators; its physical pass remains pending. Vito authorized continuing the
remaining local phases. Cluster deployment is not authorized. This directory
records the reviewed v1 protocol and its implementation gates.

- [Local foundation](local-foundation.md): named lists, migration, reducer and durable local state.
- [Android keys](android-keys.md): wrapped private state and verified enrollment.
- [Android sharing](android-sharing.md): shopping transport, confirmation and retention.
- [Relay](../../server/README.md): isolated local Ktor/PostgreSQL implementation.
- [Membership body](manifest-v1.md): exact serialized authority and wire wrappers.
- [Protocol](protocol-v1.md): framing, routing, ordered operations and conflicts.
- [OpenAPI](openapi.json): machine-readable routes, authentication and bounds.
- [Security](security-v1.md): trust, enrolment, epochs and recovery.
- [Migration](migration-v5.md): schema-4 preservation and archive boundaries.
- [Operations](operations.md): evidence from existing services and version pins.
- [Review](review.md): findings, dispositions and remaining gates.
- [Fixtures](../../testing/src/test/resources/shared-list-v1): synthetic contract
  examples and binary golden vectors, checked by `:testing:test`.

S0 defined the contract and test-only crypto probes. S1 now implements Room 5,
named local lists, the actual reducer, atomic outbox/received ledger and conflict
review. All 38 semantic scenarios run through the reducer, including the reviewed
repeat-cycle amendment and restart/duplicate delivery. The isolated S2 relay now implements signed authentication, membership CAS,
PostgreSQL ciphertext storage, append/read and hints. S3 adds actual Keystore/crypto,
verified joining and recovery. S4 connects authenticated confirmed checkpoints,
persisted ciphertext retries, Room/UI revision pins, membership controls and
foreground/background delivery. The implementation plan records current gates.

No cluster command has run. Deployment, restore drills and the household pilot
remain S5 gates. A fresh authorized physical-phone sharing pass is also pending.
The [implementation plan](../shared-lists-plan.md) remains the phase overview.

S5 local operations: [deployment](../../deploy/README.md), [runbook](runbook.md)
and [frozen recovery-copy format](recovery-copy.md). Actual deployment and pilot
remain separately approved, measured gates.
