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

Android’s **Review your usuals** replaces the six-card suggestion row with a
one-item review, in rounds of up to ten. Ranking counts distinct appearances in
the last eight shops, then uses the oldest last purchase to break ties. Add saves
immediately; Skip is session-only; Undo removes only an unchanged row created by
the review. Existing items, the last 24 hours of purchases, repeat rules, mute
and snooze choices are respected. This uses existing data and no model or new
server API. Learned interval predictions remain gated as described above.

Shopping mode, store aisle order, searched/selected history rebuy, personal
buy-next-time deferral, pinned essentials and opt-in weekly local reminders are
implemented. English, Spanish, Italian, French and German input packs load
without a registry change. Barcode product details include declared allergens
and traces, ingredients and Nutri-Score/NOVA, without any claim of safety.
A quick-settings voice tile remains later scope. Bought-together suggestions need a replay win
before shipping. Spanish, Italian, French and German UI translations are now
complete resource folders. The [translation guide](translating.md) explains how
to improve them or add another language. Input-language packs remain separate.

Lists sync through a self-hosted Kotlin/Ktor server with PostgreSQL: signed in,
the phone and the web app show the same account lists, and accounts on the same
server share lists with each other
([ADR 0017](decisions/ADR-0017-server-held-lists.md)). It replaced the earlier
end-to-end encrypted households. The [deployment package](../deploy/README.md) is
ready for review. Cluster deployment, live backup verification and a pilot with
real users require separate approval and validation. A per-list private mode,
encrypted with a key only its members hold, remains possible later.

## Paid managed hosting preparation

The [Usuals Cloud plan](hosted-service-plan.md) describes a proposed household
subscription and a separate private service layer. It predates server-held lists
and needs revisiting before use. Offline and self-hosted use
remain free. Paid onboarding, entitlement enforcement and billing are not yet
implemented. Its ordered C1 to C6 gates cover owner-approved payer binding,
client compatibility, provider sandbox tests, capacity/recovery and a separately
approved pilot. Existing server deployment material is not a paid-service launch.
