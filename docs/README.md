# Usuals documentation

Usuals is an offline smart shopping list. Signed in to a self-hosted Usuals server,
its lists sync between phone and browser and can be shared with other accounts
there. The implemented pivot and verification are recorded in the changelog and
release checks. The local `internal/pivot-plan.md`, when available, is the
detailed implementation spec.

- [Vision](vision.md): the problem, principles and boundaries.
- [Architecture](architecture.md): modules and data ownership.
- [Roadmap](roadmap.md): ordered work and completion gates.
- [Lists on a server](sync/README.md): wire contract, phone storage, identity and operator runbook.
- [Paid hosting preparation](hosted-service-plan.md): proposed Usuals Cloud boundary, billing lifecycle and launch gates.
- [Development](development.md): Docker builds, tests and emulator workflow.
- [Distribution](distribution.md): verified APKs, signing, Obtainium and F-Droid.
- [Privacy](../PRIVACY.md): device storage, optional requests, accounts and sharing.
- [Security model](security-model.md): secrets, network paths and backup.
- [Occasion packs](occasion-packs.md): personal templates, reviewed use, cross-client files and server compatibility.
- [Shopping stats](shopping-stats.md): optional selected-list history facts and manual review ordering.
- [Shared-list notifications](shared-list-notifications.md): optional quiet activity, device/account choices and delivery limits.
- [Providers](providers.md): optional model configuration.
- [Server connection](server-connection.md): connecting a phone, sharing a list and server AI.
- [Release checks](release-check.md): final local validation and distribution limits.
- [Known issues](known-issues.md): current limitations.
- [Decisions](decisions/README.md): current and historical ADRs.
