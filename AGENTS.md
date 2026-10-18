# AGENTS.md: working on Usuals

Read `internal/pivot-plan.md` first when it is available. It is the settled pivot
spec. ADR-0012 supersedes the former retailer invariants.

## Product and boundaries

Usuals is an offline Android shopping list that remembers purchases on the
phone. Capture is local. The optional model is only for unstructured recipes.
There are no retailer integrations. Sync needs explicit approval before work
starts because it requires a server on the maintainer's cluster.

`:core:model`, `:core:parse`, `:llm` and `:enrich` are framework-free Kotlin.
`:data:local` owns Room, `:auth` owns secrets, `:capture` owns capture adapters,
and `:app` owns UI and composition. New `:smart` and `:recipe` modules are pure
JVM. `ServiceLocator` must construct every production component.

## Rules

- Use `scripts/dgradle` for every build and test. Never invoke Gradle directly.
  Drive builds centrally if multiple agents share the tree.
- JUnit 4, Truth, `runTest`, and Robolectric are the test conventions. Test new
  behavior and regressions with deterministic clocks, stable ordering and fakes.
- Verify each pivot phase on the emulator, run the full Docker suite in the
  background, update the local pivot plan, then commit on `feat/smart-list`.
- Publish only with the maintainer's authorization. On 2026-10-18 the maintainer
  authorized private GitHub publication and, after verifying Grove/Tide themes,
  a squash and force-push. Use an explicit lease to protect concurrent updates.
  Vito subsequently authorized the initial theficos cluster deployment in this
  chat. Keep its operational follow-up local unless publication is requested.
  A public release remains outside that approval.
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
- Secrets stay in Keystore and never enter logs. Redact provider diagnostics.
  Third-party reads originate on the device. Recipe text is untrusted data.
- Use `:emoji: type: subject` commits. No attribution boilerplate in artifacts.
  No em dashes in prose, comments or documentation.

See `CONTRIBUTING.md`, `docs/development.md`, and `docs/security-model.md` for
build, contribution and trust details.
