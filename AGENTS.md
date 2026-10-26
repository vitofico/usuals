# AGENTS.md: working on Usuals

Read `internal/pivot-plan.md` first when it is available. It is the settled pivot
spec. ADR-0012 supersedes the former retailer invariants.

## Product and boundaries

Usuals is an offline Android shopping list that remembers purchases on the
phone. Capture is local. The optional model is only for unstructured recipes and
written-list and receipt photos, with explicit input submission and review.
There are no retailer integrations. Signed in to a Usuals server, the account's
lists live on that server, the phone and the web app show the same lists, and
accounts on the same server share a list by adding each other
([ADR 0017](docs/decisions/ADR-0017-server-held-lists.md)). Signed out, lists
never leave the phone. Work against a server on the maintainer's cluster,
including any deployment, needs explicit approval before it starts.

`:core:model`, `:core:parse`, `:llm` and `:enrich` are framework-free Kotlin.
`:data:local` owns Room, `:auth` owns secrets, `:capture` owns capture adapters,
and `:app` owns UI and composition. New `:smart` and `:recipe` modules are pure
JVM. `ServiceLocator` must construct every production component.

## Rules

- Use `scripts/dgradle` for every build and test. Never invoke Gradle directly.
  Drive builds centrally if multiple agents share the tree.
- Before submission, run `scripts/check-ci` for the complete local gate, or its
  `android`, `server` and `secrets` modes for every affected surface. Android
  checks include release builds and lint; server checks include PostgreSQL and
  browser protocol tests. UI changes still need emulator verification.
- JUnit 4, Truth, `runTest`, and Robolectric are the test conventions. Test new
  behavior and regressions with deterministic clocks, stable ordering and fakes.
- Verify each pivot phase on the emulator, run the full Docker suite in the
  background, update the local pivot plan, then commit on `feat/smart-list`.
- Publish only with the maintainer's authorization. On 2026-10-18 the maintainer
  authorized private GitHub publication and, after verifying Grove/Tide themes,
  a squash and force-push. Use an explicit lease to protect concurrent updates.
  This repository is public: never commit the maintainer's own deployment
  details (hostnames, IPs, node names, deployment records, backup or operator
  paths, production anecdotes). They belong in the maintainer's private
  infrastructure repository; examples here use placeholders such as
  `usuals.example.org`.
  On 2026-10-19 Vito authorized public repository publication, production Android
  releases, Obtainium distribution and an F-Droid inclusion submission. He also
  explicitly approved a new Usuals signing identity, its encrypted recovery backup
  and GitHub signing secrets. Each deployment needs its own authorization.
- Versions live in `gradle/libs.versions.toml`. Prefer existing dependencies.
  No Play Services, Firebase, ML Kit, analytics or crash reporting.
- English UI resources live in `:app` `values/`. A new UI language adds only
  complete `values-xx/` files. No user-facing sentence is built outside `:app`.
  Input-language packs are separate and must preserve English and Spanish input.
  The authorized browser client has its own complete locale catalogs in `web/`;
  backend APIs still return structured codes, never UI sentences.
- Keep logic deterministic. Inject clocks rather than reading wall time in
  algorithms. Construct barcodes through `Gtin.parse`.
- Bump `NORMALIZER_VERSION` when normalization changes and `PROMPT_VERSION` when
  model prompts or response contracts change. Keep the exported Room schema.
  Schemas 1 to 3 were unreleased and may be wiped. From finalized version 4 on,
  every schema change needs a migration and a migration test.
- Delete code a change makes dead. Comments explain reasons, not mechanics.
- Brand artwork comes from `web/assets/mark.svg`. Regenerate its Android,
  README and store exports with `scripts/sync-brand-assets`; use `--check`
  to verify consistency rather than drawing separate logos.
- Secrets stay in Keystore and never enter logs. Redact provider diagnostics.
  Recipe URL reads and direct model calls originate on the device. Server AI
  uses configured provider destinations after explicit input consent. Recipe
  text is untrusted data.
- Use `:emoji: type: subject` commits. No attribution boilerplate in artifacts.
  No em dashes in prose, comments or documentation.

See `CONTRIBUTING.md`, `docs/development.md`, and `docs/security-model.md` for
build, contribution and trust details.
