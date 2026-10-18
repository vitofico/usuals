# Roadmap

The ordered smart-list implementation phases are complete. The private GitHub
source preview does not include a signed APK release or a deployed service.
The [release record](release-check.md) documents the final checks and remaining
limits. The local `internal/pivot-plan.md`, when available, is the detailed spec.

| Phase | Implemented scope | Verification |
|---|---|---|
| 0 | Remove retailer features and obsolete contracts | Old-schema upgrade, add/check/remove, zero-network scanning |
| 0b | English defaults, file-only translations, input packs | Missing-key rejection and per-app locale discovery |
| 1 | Trips, purchases, Undo, transfer, archives, dated import, text sharing | Idempotent close, exact Undo, second-emulator restore |
| 2 | Editing, categories, history autocomplete, explicit repeats | Sticky category and exact day-21 repeat boundary |
| 3 | Six-slot suggestion row, staples, interval replay | Intervals remain off; no real household history supplied |
| 4 | Quantity hints, offered repeat rules, confirmed aliases | Explicit 1 l survives a displayed 2 l hint |
| 5 | Structured recipes and optional reviewed model fallback | Ten real device URLs, blocked-page message, real local model |
| 6 | Opt-in scan naming, store tags, home-screen widget | Zero traffic while off, ten immediate rows, offline naming, widget updates |
| 7 | Current docs, ADRs and final release checks | Docker suite, secret scans and emulator run-through |

## Before distribution

The pivot is ready for source publication. No release tag, APK distribution or
store listing has been created. Native 16 KB alignment now passes in both APK
variants and is checked in both APK workflows. A Motorola Android 15 smoke test
also passes. Repeat build and device checks for the actual release artifact
before distributing an APK. The API 37.1 emulator previously
showed rendering/input delays. These limits are tracked in
[known issues](known-issues.md).

## Next evidence and later features

Keep recording real closed or imported trips. Re-run the chronological replay
after 8 to 10 weeks of real use. Enable intervals only if they beat both last
trip's items and top-six staples on held-out top-six hits. Synthetic fixtures
verify mechanics and cannot establish that result.

Shopping mode, store aisle order, searched/selected history rebuy, personal
buy-next-time deferral, pinned essentials and opt-in weekly local reminders are
implemented. English, Spanish, Italian, French and German input packs load
without a registry change. Barcode product details include declared allergens
and traces, ingredients and Nutri-Score/NOVA, without any claim of safety.
A quick-settings voice tile remains later scope. Bought-together suggestions need a replay win
before shipping. Spanish, Italian, French and German UI translations are now
complete resource folders. The [translation guide](translating.md) explains how
to improve them or add another language. Input-language packs remain separate.

The [shared lists plan](shared-lists-plan.md) records the local Kotlin/Ktor relay,
PostgreSQL storage, encrypted Android sharing and recovery implementation. Its
[deployment package](../deploy/README.md) is ready for review. Cluster deployment,
live backup verification and the household pilot require separate approval and
validation.

## Paid managed hosting preparation

The [Usuals Cloud plan](hosted-service-plan.md) describes a proposed household
subscription and a separate private service layer. Offline and self-hosted use
remain free. Paid onboarding, entitlement enforcement and billing are not yet
implemented. Its ordered C1 to C6 gates cover owner-approved payer binding,
client compatibility, provider sandbox tests, capacity/recovery and a separately
approved pilot. Existing relay deployment material is not a paid-service launch.
