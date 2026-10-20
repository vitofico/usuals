# Local smart-list release check

## Private source publication

The maintainer approved a private GitHub source preview on 2026-10-18, with all
development history squashed into one initial commit. Commit IDs in the earlier
records below identify pre-publication local history, retained in the
maintainer's local Git bundle; they are not refs in the published repository.
The initial publication changed README assets and documentation. The palette
follow-up below records verification of the subsequent UI changes. Source
publication does not publish a signed APK or deploy the cluster service.

## Shared Android and web icon, 2026-10-19

Android now uses the preferred dotted U from `web/assets/mark.svg`, with its
original aubergine/champagne colours in the list header and empty state. The
adaptive launcher's monochrome layer and reminder notification reuse the same
foreground drawable. The obsolete double-line basket asset is removed, and
the repository-native README cover uses the same mark.

- Docker debug/release builds, `test` and `lint` pass through `scripts/dgradle`
  in 13m 6s. The reports cover 876 cases, zero failures/errors and five opt-in
  live skips. App lint retains 94 warnings/11 informational findings, zero errors.
- Both APKs pass ELF/ZIP 16 KB alignment for all 12 native libraries. Debug
  SHA256: `8c8ee47d26a8f6022bc72c7118949a970edb69421364f8dbc06b7935ea58bd20`.
  Release: `01dc5c4aca511238811595c41deaafb451392aeee45c39cbd9ff6504609201a3`.
- XML/SVG checks confirm matching web glyph paths/colours and adaptive safe-area
  bounds. Themed icons share the foreground resource, rather than a second copy.
- Emulator screenshots verify the actual header, empty state and Android-rendered
  adaptive icon. Its existing Comanda package/data was left intact; the Usuals
  build was installed separately. The emulator disconnected after visual checks.
- No schema, permission, language, dependency or server change. The phone also
  disconnected before installation of this update; reconnection is requested.

Evidence: `/private/tmp/usuals-icon-qa/`, including `build-proof.json`,
`source-checks.json`, `android-gate.log`, `app.png` and `app-info.png`.

## Selectable palettes, 2026-10-18

Grove and Tide join the original Plum appearance. Selection applies immediately
and persists per device/browser. All three follow system light/dark mode, with
matching Android system bars, widget resources and browser colors. Android and
web copy is complete in English, Spanish, Italian, French and German.

- Full Docker Android debug/release builds, Android-test APK, unit tests and
  lint pass: 876 JUnit executions, zero failures/errors, five opt-in live skips.
  App lint retains 94 warnings and 11 informational findings, with zero errors.
- Both final APKs pass 16 KB ELF/ZIP alignment for all 12 native libraries.
- Docker PostgreSQL/server integration and shared-module tests pass 132 cases;
  browser tests and bidirectional Kotlin/WebCrypto interoperability pass 99.
- The 16 KB emulator passes all six palette variants, selection/restart and
  matching status/navigation-bar pixels. All five selectors render correctly;
  German also passes at 1.3 font in dark mode. Robolectric verifies actual
  widget layouts and colors for all six variants.
- A synthetic locked-browser preview verifies all three palettes, reload
  persistence, keyboard selection and the 390px dialog/header layout, including
  German. Manual browser checks use system dark mode; automated contrast tests
  cover both modes. No real account or browser vault is created for this pass.
- The emulator's original private files are restored exactly before reopening;
  all facts in its 19 database tables match. Locale/font/night settings are
  restored. The final debug APK remains installed. The phone is untouched.
- No schema, dependency, server API or encryption contract changed. The local
  fixture is stopped and its database/credentials removed; browser viewport is
  reset. No cluster, SSO or payment-provider configuration was changed.

Evidence is retained locally in `/private/tmp/usuals-themes-qa/` and the
`usuals-themes-android-release-gate.log`, `usuals-themes-server-web-release-gate.log`
and `usuals-themes-native-alignment.log` files beside it. The pre-theme source is
retained in the maintainer's ignored Git bundle before the authorized squash.

## Original pivot verification

Date: 2026-10-15. Branch: `feat/smart-list`, based on `main` at `389354e`.
Implementation through phase 6: `8490424`. Phase 7 reconciles documentation,
removes retired code comments, corrects privacy copy and validates the backup
allowlist. No remote, push, pull
request, tag, deployment or cluster relay was created.

## Phase evidence

Every phase had a Docker build/full test invocation, emulator verification and
a local commit. Counts include Android debug/release unit variants.

| Phase | Commit | Tests | Device acceptance evidence |
|---|---|---:|---|
| 0 | `994be6e` (after `23ef80f`) | 541 | Installed old schema-3 build, upgraded, added/checked/removed/scanned; no scan traffic |
| 0b | `8d07d71` | 546 | English while system locale was Spanish; incomplete Italian resources failed, complete resources appeared in the system locale picker |
| 1 | `ba3bd99` | 591 | Finish/Undo, dated import, SAF export and exact shopping-table restore on a second emulator |
| 2 | `f827cf4` | 619 | Fresh categories, sticky manual override, explicit quantity, day-21 repeat and Undo |
| 3 | `d31344a` | 626 | Add/Not now/Never, persistent choices and snooze expiry; interval gate stays off |
| 4 | `96ecbe0` | 637 | Explicit 1 l survives a 2 l hint, tap saves hint, confirmed merge and offered 21-day rule |
| 5 | `457cb73` | 657 | Ten real recipe URLs without a model, clear blocking response, original prose through an actual local model, zero-budget/restart checks |
| 6 | `8490424` | 671 | Ten immediate scan rows and unchanged UID bytes while off, real naming/category/ODbL confirmation, airplane-mode name, store filter and live widget |

All phase suites had zero failures/errors. Phase 5 includes two opt-in recipe
live contracts. The ordinary final suite skips network-dependent live tests;
that is separate from the recorded device reads. The ten real recipe sources
and replay command are in [development](development.md).

## Final gate

- Docker `scripts/dgradle :app:assembleDebug test lint --no-watch-fs` passed.
  A forced full run preceded the final affected-input rerun. Reports contain
  671 tests, zero failures/errors and 5 explicitly opt-in live checks skipped.
- Lint passed with zero errors; the app report retains 79 warnings, mainly
  dependency/tooling update recommendations. No lint baseline was introduced.
  Removed three unused colors and converted two count strings to plurals.
- Fixed redundant transfer exclusions outside the database allowlist. The
  backup regression asserts the exact database-only include set. Android's
  [include-list semantics](https://developer.android.com/identity/data/autobackup#IncludeFiles)
  exclude other domains, including preference secrets.
- Docker secret scans of Git history and staged changes found no leaks. A final
  staged scan covers the completed record.
- Current Markdown links resolve locally, and tracked prose has no em dashes
  or attribution boilerplate.
- The final installed APK passed fresh check/Finish/Undo, preserving Milk's
  4 l quantity and store tag, plus capture and swipe removal. Its privacy screen
  displayed the speech-service disclosure. Scan opt-out survived restart and
  was independently verified false in the persisted preference file.
- A SAF export/reimport preserved all 28 active rows. Its 9 trips and 12 purchases
  exactly matched the earlier phase-5 history, so the temporary close/Undo and
  swipe introduced no purchase facts. The archive contains 30 item records,
  4 preferences and 3 aliases, with no cache, provider or secret data.
- The final widget showed 28 unchecked items and opened the living list. Android
  event logs recorded no additional Usuals ANR during this final run. The two
  earlier emulator ANRs remain a documented distribution limit.

The pivot is ready for the maintainer to create the GitHub remote and publish
its source. This is not a signed APK distribution or store-release approval.

Local debug APK SHA-256:

```
3f94247dbd2a8c56329dc12190d1a71e06c1cabc6408a4fc5caba136aa6da5f1
```

## Native 16 KB follow-up (bfda760)

The post-pivot fix updates all CameraX artifacts from 1.3.4 to 1.4.2 without
changing SDK levels or the data model. The old image-processing library had
4 KB ELF LOAD alignment; graphics and APK ZIP alignment were already correct.
No compatibility-warning suppression or legacy packaging workaround was added.

- Docker `scripts/dgradle :app:assembleDebug :app:assembleRelease test lint --no-watch-fs`
  passed: 671 tests, zero failures/errors, 5 opt-in live skips. App lint retains
  79 warnings and zero errors.
- Both APKs pass `scripts/check-native-alignment.py` for all 12 native libraries
  across arm64-v8a, armeabi-v7a, x86 and x86_64. Android Build-Tools 36
  `zipalign -c -P 16 4` independently passes both APKs.
- The checker rejects the original APK's four incompatible CameraX binaries.
  Separate fixtures prove it rejects bad ELF and bad ZIP alignment and accepts
  aligned libraries. Both APK workflows now check alignment before upload.
- `emulator-5554` reports `PAGE_SIZE=16384`. With linker backcompat set to `fatal`
  and package-manager backcompat disabled, the updated app launches without a
  warning and opens live CameraX preview. Android reports `pageSizeCompat=0`,
  compared with `4` for the old APK.
- Check/Finish/Undo preserves Milk's 4 l quantity and market tag. The list returns
  to 28 unchecked rows; database comparisons preserve all 9 trips, 12 purchases,
  preferences, aliases and cached product data. No new app crash or ANR occurred.
- The debug APK SHA-256 is
  `12976c68fef66a892a2b47a4063ea2ce4082dcd23399257710396d34f1729ca2`.
  The release variant is debug-signed locally, not a distribution artifact.

The build/test log, checker regression log and emulator evidence are retained
locally under `/private/tmp/usuals-16k-*`. The phone follow-up below ran after
this fix was committed locally.

## Phone test and preview correction

The connected Motorola edge 40 neo runs Android 15 / API 35 with 4096-byte pages.
It had no installed Usuals package. The debug APK was installed alongside the
phone's other apps; no pre-existing app data was reset.

- Fresh startup displays English on the Italian system. Local text capture,
  check/Finish/Undo, typed-barcode insertion and manual naming all pass. Product
  lookup stays off; no model is configured.
- Visual checks found the camera texture extending beyond its allocated preview
  area on both devices. Clipping the PreviewView to its rectangular outline
  keeps the introduction, barcode field and Add button clear. Final screenshots
  on both devices verify the correction, with active camera sessions recorded.
- The final Docker debug/release build, full tests and lint pass again: 671 tests,
  zero failures/errors, 5 live skips. Both APKs retain 12 aligned native libraries
  and independently pass `zipalign -P 16`.
- The final APK starts and opens camera preview on the 16 KB emulator with
  compatibility fallback disabled. Its 28-row list remains intact. The phone
  returns to an empty list after UI removal of temporary rows; no test trips or
  purchases remain. No app crash or ANR was recorded on either device.
- Emulator compatibility properties were restored. The phone keeps the final
  debug app installed on Home. This is a functional smoke check on one physical
  device, rather than a performance benchmark or signed-release certification.

Final debug APK SHA-256:

```
e1dde7c1def3d14a8172b78dd7412f57060801f5c042bd337dbdb8b56d7e7f0d
```

The final build log is `/private/tmp/usuals-16k-preview-build-test.log`.
Alignment, UI, camera and event evidence use the same local `usuals-16k-` prefix.

## Extended recipe and scanner QA

The Motorola Android 15 pass resumed after reconnection. Testing then moved to
simulators when the maintainer needed the phone. Changes remain local.

Recipe reads now allow dismissal and cancel the underlying HTTP call, including
a body download. Ingredient insertion still prevents dismissal while committing
(ffa1265). Further camera testing found isolated checksum-valid misreads and an
idle screen timeout. The analyzer now requires three consecutive matching frames
before insertion/debounce, and the visible preview keeps the display awake.
Existing CameraSession and ServiceLocator wiring is retained; no production
class, dependency, schema or UI string was added.

- Final Docker `scripts/dgradle :app:assembleDebug :app:assembleRelease test lint -PrecipeLive=true --no-watch-fs`
  passed: 682 tests, zero failures/errors, 3 opt-in Open Food Facts skips. App
  lint retains 79 warnings, 11 informational findings and zero errors.
- Regressions cover transport cancellation, Close while reading, another import
  after cancellation, isolated valid-checksum camera outliers, interrupted
  confirmation, reset, padded/rotated frames and holding a code past debounce.
- Both live recipe contracts previously passed with unchanged recipe inputs:
  all ten public pages produced ingredients, and blocking was reported clearly.
- Both final APKs pass native checks for all 12 libraries across four ABIs and
  SDK `zipalign -c -P 16 4`. Debug SHA-256:
  `ca053eefa9458fd5048273bb9c989953b6e0e0b0de956ac9b257a37f8b9247f4`.
  Release SHA-256:
  `e0a1be8afb6fa9f2f71614610eb7925fe36ca0b84f1e72715f18ddc2a5f7429e`.

Physical-phone checks passed shared and manually entered recipe URLs, four
selected ingredient inserts with quantities, repeat import excluding all four
without any database change, a public BBC recipe, invalid/blocked/non-HTML
feedback, prose fallback and the missing-model message. Close cancelled a slow
read in 2.6 seconds before response headers arrived. No model was configured or
called. Check, Finish shop, Undo and uncheck also passed; Undo restored the exact
original one trip and two purchases.

The camera captured the intended real barcode before frame confirmation was
added, alongside isolated misreads. Eight unnamed scan-test rows were removed
through the UI; the confirmed product name and original purchase facts were
preserved. At phone handoff, four recipe test rows remained: onion, carrots, milk
and pasta. The phone disconnected before their final cleanup. No database
replacement occurred. Temporary keep-awake was restored to its original Off
setting and the recipe forwarding removed, both verified. The phone retains the
earlier same-source debug build `d7d05c4a...`; it was not used again after handoff.

Further simulator checks passed:

- Fresh startup and camera permission/preview on a 16 KB kernel without a
  compatibility warning. The preview stays awake beyond the effective idle
  timeout, and closing it restores normal screen sleep.
- Invalid checksum, zero and GTIN-14 rejection with no database changes;
  EAN-13/EAN-8/UPC-A canonicalization, duplicate identity without guessed
  quantities, and ten immediate scan rows with unchanged app UID network bytes
  while lookup was off. Typed fallback works without camera permission.
- Live Open Food Facts naming/category/ODbL offer, explicit confirmation and
  confirmed alias reuse with lookup off and unchanged UID bytes.
- Finish/Undo, quantity/category/store edits, store filtering, repeat persistence
  and removal. Future dated history is rejected; a past shop creates exactly two
  purchase facts and displays the chosen local date.
- SAF export/import preserves every database table, including the existing
  product cache. The archive itself excludes cache and model/settings data.
  Sharing opens the system chooser with quantities and returns without sending.
- Shopping data survives a full simulator restart. The existing widget shows
  28 items, six rows plus the remaining count, and opens the living list from
  Settings. The original emulator's entire database remains unchanged, including
  28 active rows, 9 trips and 12 purchases.
- The exact final debug APK was installed and hashed on the original emulator;
  Home, native camera/typed preview and return passed without changing any data.
  The collected final simulator event snapshots contain no app crash/ANR events.

Optical acceptance of the final confirmation rule remains a physical-device
retest. The simulator image camera rendered the barcode fixture, but neither
the previous analyzer nor the new one decoded it. A comparison build changed
only that analyzer and restored the final source/APK afterward; the final full
Docker gate was rerun. Live voice was not exercised in this follow-up.

The disposable simulator and its files, localhost recipe fixture and its own
forwarding were removed. Original emulator camera permission, animation scales
and display settings were restored. The original emulator keeps the final APK
and its original shopping data; the maintainer's existing local model service
was untouched.

Local evidence is under `/private/tmp/usuals-phoneqa-*`: `final-verified.log`,
`final-native-verified.log`, `final-zipalign-verified.log`, phone recipe/core and
handoff logs, `isolated-barcodes-final.log`, `isolated-naming-final.log`,
`isolated-editor-verified.log`, `isolated-export-final.log`,
`isolated-camera-comparison.log`, `original-widget.log`,
`final-artifact-emulator.log`, exact database snapshots and `cleanup.log`.

## Scanner flow correction (2026-10-16)

The maintainer reported repeated camera codes and numeric rows with no product
name. Camera capture now pauses after one accepted code until Scan next item is
chosen. Each canonical code is accepted once per session, including after
rotation. Posted callbacks from an old preview are ignored. Typed entry still
supports adding different products without waiting for queued network reads.

The scanner exposes the persisted Open Food Facts opt-in and name review directly.
An unnamed scan explains disabled lookup, a missing product name, a failed read
or a store-specific code. Explicit enable and retry query the existing row rather
than inserting again. Confirmed aliases reuse names offline after Finish or
removal. The saved name appears in scanner feedback. New provider names still
require confirmation; lookup remains off by default.

Regression coverage includes camera callback bursts, old preview callbacks,
duplicates, rotation, the explicit opt-in, name lookup retry without another
insert, late results after Skip, lookup outcome explanations and name reuse after
Finish/removal. The navigation test invokes the accessible actions; simulator QA
checks the actual touch targets and real network path. Existing ServiceLocator
factories construct every collaborator. No dependency or schema changed.

The simulator offered Coca-Cola Original, Drinks and ODbL credit for barcode
5449000000996. Enabling lookup and confirming its name produced one new row.
Rescanning within the session left every table unchanged. A subsequent session
with lookup off reused the confirmed name without changing any table. The phone
was not accessed. The camera confirmation's physical optical retest remains
pending, as recorded above; deterministic camera fixtures and simulated camera
callbacks cover this follow-up.

Final Docker `scripts/dgradle :app:assembleDebug :app:assembleRelease test lint -PrecipeLive=true --no-watch-fs`
passed: 696 tests, zero failures/errors, 3 opt-in Open Food Facts skips. Lint has
zero errors, 79 warnings and 11 informational findings. Both final APKs pass the
native checker for all 12 libraries across four ABIs and SDK zipalign with
16 KB alignment. Debug SHA-256:
`8b2e780519f8094ce9fa8e0c85a3fb89488521443495645d5b77b59d0dca3201`.
Release SHA-256:
`b71e79f6082ef9c4a97b01c044601d56e4bd1b966b2abd880d1b658b2c5d3664`.

Local evidence uses `/private/tmp/usuals-scan-flow-*`: `final-gate.log`,
`final-native.log`, `final-apk-hashes.json`, `final-ui.log` and simulator XML,
screenshots and exact database snapshots under `usuals-phoneqa-scan-flow-*`.
The exact final debug APK is installed on emulator-5554. After the final touch
pass, every database row matched the original backup: 28 active items, 9 trips,
12 purchases and the original aliases, preferences and cache. Lookup was restored
to off, and resolution/density returned to their original settings. The final
collected simulator event snapshot contains no app crash or ANR events.

## Distribution boundary

This check establishes the local pivot's implemented behavior. It does not
certify a production APK or store release. Intervals require a win on real
household history, and remain disabled. Native alignment is resolved by the
follow-up above. The API 37.1 emulator previously showed input ANRs; the first
captured trace has a runnable Compose drawing thread during heavy
graphics/interrupt load. The physical smoke test above passed; repeat device
checks on the eventual release target before APK distribution.

The pivot used `emulator-5554` and a temporary restore emulator. The native fix
also passed the authorized physical-phone smoke test above. Model and barcode
lookup remain optional; temporary QA provider settings are not part of the app.
Sync is unbuilt and requires approval before work starts.


## Visual redesign follow-up (2026-10-16)

- Added warm cream and forest green light/dark themes, bundled Manrope, grouped
  grocery cards, visible check circles, quantity badges and category icons.
  Added a direct keyboard action beside voice and scanning. Recipe review,
  scanner, history, Settings, widget and launcher follow the same visual system.
  Settings sections expand in place; dated past-shop entry opens in a sheet.
- The font and SIL OFL 1.1 are bundled in both APKs. No new service, dependency,
  schema or composition collaborator was added. All new UI copy lives in :app.
  See [visual design](design.md) for the palette and interaction conventions.
- Final background Docker command:
  `scripts/dgradle :app:assembleDebug :app:assembleRelease test lint -PrecipeLive=true --no-watch-fs`.
  Reports contain 703 tests, zero failures/errors and 3 opt-in OFF skips.
  Lint has zero errors, 79 existing warnings and 11 informational findings.
  No warning suppression was added. Contrast tests cover both themes; gesture
  tests use native graphics where rounded clipping requires real geometry.
- Both final APKs pass ELF and ZIP checks for all 12 native libraries across
  four ABIs, plus SDK `zipalign -c -P 16 4`. The final debug APK is installed on
  the 16 KB emulator. Its hash matches the host artifact.
- Emulator checks cover keyboard capture, check/Finish/Undo, long-press editing,
  store filtering, swipe removal, public JSON-LD recipe review, selection and
  insertion, live catalog naming, past-shop import and Settings disclosures.
  Light/dark themes, 130 percent text and widget navigation were checked.
  Camera preview remains clipped. Optical capture on the physical phone remains
  deferred as documented above; the phone was not used for this redesign.
- Restored all original emulator database tables and shared preferences, with
  28 active items, 9 trips and 12 purchases. Restored original display settings.
- Evidence: `/private/tmp/usuals-redesign-final-test.log`,
  `usuals-redesign-final-results.json`, `usuals-redesign-final-artifact-checks.log`,
  `usuals-redesign-latest-qa.log`, `usuals-redesign-final-qa.log`,
  `usuals-redesign-final-restoration.log` and `usuals-phoneqa-redesign-*.png`.

Debug SHA-256: `01d5434141ba23a0e95f433ec868786a28ec53d5cb516b92e8c06c8a88adc667`.
Release SHA-256: `ca644859a6d66bdffb13f807b51b360575ec2333eb4e92a68d2bfc1440fc8ed2`.


## Bold and colourful direction (2026-10-16)

The maintainer selected bold and colourful after the initial warm design. The
iteration used violet, lime and coral, a solid household header, extra-bold
Manrope, category colours and stripes, lime recipe selections, and matching
history, scanner, widget and launcher treatments. The empty-list view omits its
illustration on compact screens and scrolls so larger text remains reachable.

- Final background Docker debug/release build, full tests and lint passed:
  703 tests, zero failures/errors, 3 OFF live skips. Lint has zero errors,
  79 existing warnings and 11 informational findings. Contrast checks now cover
  category counts in both themes as well as the main text and control roles.
- Both APKs pass ELF and ZIP alignment for all 12 native libraries across four
  ABIs, SDK `zipalign -c -P 16 4`, and bundled font/license verification.
- Emulator checks passed keyboard capture, public recipe review, live catalog
  naming and confirmation, history entry, Settings disclosures, filtering,
  check/Finish/Undo, dark mode, 130 percent text, camera preview and widget
  navigation. A synthetic empty database verified the compact layout at
  130 percent text. Original data, preferences and display settings were restored
  and verified, including all original tables and 28 active items.
- No service, dependency, schema or composition collaborator was added. The
  phone was not used. Optical capture remains deferred as recorded above.
- Evidence: `/private/tmp/usuals-bold-final-test.log`, `usuals-bold-qa.log`,
  `usuals-bold-compact-qa.log`, `usuals-bold-final-results.json`,
  `usuals-bold-final-artifact-checks.log`, `usuals-bold-final-restoration.log`
  and `usuals-phoneqa-bold-*.png`.

Debug SHA-256: `2b77b57104e8b7b362b3ed087a8fd0d8da96e270327091f921734759d346e35f`.
Release SHA-256: `c38a6234c57d28b33b32bd8b4fd59f587c39b8c1e456d40b098f6fef69f44e9c`.


## Design refinement and shared-list planning (2026-10-16)

Refined the colourful direction with an open header, softer surfaces, tinted
category glyphs, neutral quantities, inset dividers and a lighter capture dock.
Recipe review, history and the widget follow the same hierarchy. Removed the
unused extra-bold font registration. See [design.md](design.md).

- Final background Docker gate passed in 3m 12s:
  `scripts/dgradle :app:assembleDebug :app:assembleRelease test lint -PrecipeLive=true --no-watch-fs`.
  Reports contain 703 tests, zero failures/errors and 3 opt-in OFF skips.
  Lint has zero errors, 79 existing warnings and 11 informational findings.
- Both themes pass text/control contrast tests, including category glyphs on
  their actual tinted backgrounds. Both APKs pass ELF/ZIP native alignment for
  12 libraries across 4 ABIs, SDK 16 KB zipalign, and font/license byte checks.
- Emulator touch checks passed keyboard entry, public recipe review, live catalog
  naming and confirmation, history entry navigation, Settings disclosure,
  filtering, check/Finish/Undo, dark mode, 130 percent text, preview and widget.
  The final APK also passed a compact empty-list check at 130 percent text.
  Optical barcode capture requires a physical camera and was not retested.
- Restored and compared every original table, preferences and display settings:
  28 active rows, 30 item records, 9 trips, 12 purchases, 4 preferences, 3 aliases
  and 1 local product-cache row. The installed emulator APK matches the host.
  The phone was left available to the maintainer.
- No production collaborator, dependency, data schema or network behavior changed.
  ServiceLocator wiring remains intact. UI resources remain in `:app`.
- The [shared lists plan](shared-lists-plan.md) recommends Ktor/PostgreSQL on
  the existing cluster with encrypted payloads, offline conflict handling,
  invitations, revocation/recovery and S0 to S5 acceptance gates. This is planning
  only; relay implementation and deployment require approval before starting.
- Evidence: `/private/tmp/usuals-refined-final-test.log`,
  `usuals-refined-qa.log`, `usuals-refined-compact-qa.log`,
  `usuals-refined-final-results.json`, `usuals-refined-final-artifact-checks.log`,
  `usuals-refined-final-restoration.log` and `usuals-phoneqa-refined-*.png`.

Debug SHA-256: `b086ab5dad99828b5f32201acbc5f6fc87d6bdf32340510aaa9ebb0307ce27cb`.
Release SHA-256: `4db9bfed86f11ea27cf66dd785648348db46c4080e28850097c24ac5c4c06f3b`.


## Shared lists S0 contract (2026-10-16)

Local implementation was authorized starting with S0. The [contract](sync/README.md)
adds an ordered encrypted protocol, OpenAPI, membership/recovery boundaries,
Room-5/archive-2 design, 29 semantic reducer scenarios and 28 security scenarios.
Cluster conventions were learned from local Quire, Spunto and theficos-cluster
files. No cluster API, SSH, credential use, deployment or publication occurred.

- Independent read-only review found seven contract gaps. All were fixed and
  re-reviewed as closed; the [review record](sync/review.md) keeps dispositions.
- Eleven test-only probes verify binary framing, the public RFC Ed25519 vector,
  RAW Tink AEAD/JCA layout, tamper/AAD/recipient rejection, API auth/capability
  shape, fixture integrity and the real schema-4 identity. The semantic/security
  scenarios are acceptance inputs for later implementations, not executed
  reducer/auth/migration/restore tests.
- Final background Docker gate passed:
  `scripts/dgradle :app:assembleDebug :app:assembleRelease test lint -PrecipeLive=true --no-watch-fs`.
  714 tests, zero failures/errors, 3 opt-in OFF skips. Lint: zero errors,
  79 existing warnings, 11 informational findings. No new test-code warnings.
- Debug/release native ELF/ZIP and SDK 16 KB alignment passed for 12 libraries
  across four ABIs; bundled font/license bytes passed. The final debug APK was
  installed on the 16 KB emulator and its hash checked after verification.
- Final emulator smoke passed keyboard capture, history, filtering, checking,
  Finish/Undo and Settings. All original tables, preferences and display settings
  were restored and compared: 28 active items, 30 records, 9 trips, 12 purchases,
  4 preferences, 3 aliases and 1 local product cache. The phone was not used.
- No production sync class, schema change or new network path was added.
  ServiceLocator needs no new collaborator in S0. S1 must implement the reducer,
  versioned migration, atomic outbox and named-list UI. Deployment needs approval.
- Evidence: `/private/tmp/usuals-s0-final-test.log`, `usuals-s0-final-qa.log`,
  `usuals-s0-final-results.json`, `usuals-s0-final-artifact-checks.log`,
  `usuals-s0-final-restoration.log`, `usuals-s0-review-scope.json`,
  `usuals-s0-maven-pins.json`, `usuals-s0-production-dependencies.log` and
  `usuals-phoneqa-s0-*.png`.

Debug SHA-256: `d9c3a1252b14f34218e05d17904c37cf6f4825e7c78b55e23fa0467e268c6368`.
Release SHA-256: `ac7d8057db45dbae3ef8230b28fd106154b05150b0af27324b9c10c7f69059ee`.

## Shared-list S1 local foundation (2026-10-16)

- Added named local lists, actual migration 4 to 5 and the exported Room schema,
  pure `:sync` reducer, transactional source intents/outbox, contiguous received
  ledger/cursors, rebasing and local conflict review. Every production
  collaborator is constructed through ServiceLocator.
- Final background Docker debug/release builds, full tests and lint passed:
  746 tests, zero failures/errors and 3 opt-in skips. Lint has zero errors,
  79 existing warnings and 11 informational findings. All 38 semantic reducer
  scenarios pass replay, duplicate delivery and serialized restart.
- The independent protocol follow-up resolved next-shop repeat re-arming with
  explicit cycle lineage and closed its two delta findings. Room tests cover
  original facts, all units, nulls, indexes/FKs, rollback, process restart,
  archive transfer, two-replica catch-up and durable repeat Undo.
- Both APKs pass ELF/ZIP alignment across 12 native libraries/four ABIs, SDK
  16 KB zipalign, and bundled font/license checks.
- Emulator upgraded the real original schema-4 baseline without losing any
  shopping fact. Passed list create/select, isolation, history/restart,
  conflict values/persisted dismissal, live recipe add, live catalog naming,
  repeated typed barcode, camera preview, Finish/Undo, Settings and dark/large
  text. Optical capture was not retested. The physical phone was left untouched.
- Preserved all schema-4 shopping facts and original preferences, with the
  expected one-time prompt scoping. Restored every final schema-5 baseline
  table and preference after QA, display settings and 28 active items. The
  corrected final APK passed another smoke run and its installed hash matches.
- Evidence: `/private/tmp/usuals-s1-generated-final-test.log`,
  `usuals-s1-generated-migration.log`, `usuals-s1-generated-smoke.log`,
  `usuals-s1-final-qa.log`, `usuals-s1-last-smoke.log`,
  `usuals-s1-final-results.json`, `usuals-s1-final-artifact-checks.log` and
  `usuals-s1-final-restoration.log`.
- Debug SHA-256: `8ac03db505bf5f8909bcbc0e66a944200fa89360f7b62dd5175ce6deabea108d`.
- Release SHA-256: `eaeb676e5fcd26ed87a254b19e1442dc2aa11947a5a2001330900aed8decf666`.
- Local-only foundation. Relay, crypto/joining and sharing integration remain
  S2 to S4; cluster deployment still requires explicit approval. No remote/push.

## Shared-list S2 local relay (2026-10-16)

- Added the isolated Kotlin 2.3.21/Ktor 3.6 build with independent outputs and
  locked dependencies, PostgreSQL migration/ledger and persistent receipt key.
  Signed authentication, scoped package reads, membership CAS, staged snapshots,
  durable append/catch-up/intent resolution, restricted recovery and authorized
  WebSocket hints are implemented. Main composes every server collaborator;
  Android ServiceLocator is unchanged and app transport remains S3/S4 work.
- Final strict-lock PostgreSQL Docker gate passed 41 tests: 16 server, seven
  shared sync and 18 model tests. Concurrency/commit order, exact retries,
  all-or-none rollback, restart, cross-household/editor/recovery denial,
  expiry/reuse/revocation, quotas, strict parsing, HTTP contract, socket hints
  and roster-only revocation closure pass. Database inspection finds no
  synthetic shopping plaintext in operations, snapshots, manifests or packages.
- Same authorized independent reviewer closed every concrete membership/wire
  follow-up finding, including fresh public keys, required fields/nulls,
  list-scoped package lookup and committed-only conflict receipts. Creation
  wrappers/statuses match OpenAPI and have HTTP regression tests.
- Final full Android Docker gate passed: 749 tests, zero failures/errors,
  three opt-in OFF skips. Lint has zero errors, 94 warnings and 11 infos:
  88 version-update notices and six existing Android/build warnings. No new
  Android source behavior or production dependency is added in S2.
- Both APKs pass ELF/ZIP and SDK 16 KB alignment across 12 libraries/four ABIs,
  plus font/license checks. Emulator verifies list create/capture/switch and
  Finish/Undo with the final APK. Visually inspected the restored Home screen.
- Restored every schema-5 baseline table and all five preferences byte-for-byte,
  display defaults and 28 active items. Installed APK hash matches the artifact
  and the emulator reports a 16 KB page size. The physical phone was untouched.
- Evidence: `/private/tmp/usuals-s2-final-server-test.log`,
  `usuals-s2-final-android-test.log`, `usuals-s2-final-qa.log`,
  `usuals-s2-final-results.json`, `usuals-s2-final-artifact-checks.log`.
- Debug SHA-256: `b054d8cfe084a6d0802add0dca380468770e20f0b365d235210350ef72d313a9`.
- Release SHA-256: `2607f7c545d5c42060eb43fe1c51880cd7ddf829ea30772d6b0329e5ad82f9b7`.
- Test PostgreSQL containers and temporary credentials were removed. No cluster
  command, deployment, remote or push. S3, S4 and local S5 preparation follow.

## Shared-list S3 and S4 Android sharing (2026-10-16)

S3 commit `17761e9` implements Keystore-wrapped private state, real Tink
encryption, verified joining, accepted ownership transfer and recovery. Its
Docker gates passed 766 Android tests and 49 isolated server-build tests;
actual emulator Keystore, optimized-release crypto and old-provider-key
upgrade checks passed. See [Android keys](sync/android-keys.md).

S4 connects encrypted shopping transport, WorkManager and foreground sockets,
authenticated Room confirmation, frozen UI revisions, explicit barcode merges,
membership controls and local retention. Independent bounded protocol review
closed receipt continuity, source collision, merge-incarnation, recovery-fork,
session-renewal and removal-interruption findings. No schema change beyond 5.

- Full Docker debug/release/test APK builds, tests and lint passed: 799 tests,
  zero failures/errors, three OFF live skips; lint zero errors, 94 existing
  warnings and 11 infos. Final follow-up verified the instrumentation lifecycle
  source. Isolated strict-lock PostgreSQL/server build passed 53 tests, zero
  failures/errors/skips, including one session renewal over two missed epochs.
- Both APKs pass ELF/ZIP/SDK 16 KB checks for all 12 native libraries and four
  ABIs, plus bundled font/license checks. Actual 16 KB emulator Keystore tests
  pass four cases, including unavailable-key reset preserving shopping work.
  Optimized release verifies a valid encrypted kit through AES/signatures/HPKE
  to the expected offline boundary, rejects a wrong key and reopens wrapped
  private state after process restart. API26/33 crypto remains Robolectric.
- Two actual emulators verify share preview/history initially unticked,
  independent source copy, QR approval/key exchange and foreground shopping.
  Concurrent offline Finish creates one canonical 3-litre purchase/trip; losing
  Undo disappears, and accepted Undo restores the exact checked row. A frozen
  name editor preserves the other device's 7-litre edit; its edited row remains
  swipe-removable. The picker identifies shared and local scopes.
- The actual scan-event/name/merge UI requires explicit confirmation, keeps
  the displayed 2-litre target and converges to one named row. This checks the
  production scan callback and merge UI, not camera optics. Owner UI rotation
  plus real peer reboot preserves an offline queued intent, refreshes keys and
  applies it once. Owner UI removal, SAF pending export without private state,
  independent local retention and restart during private-first deletion pass.
- Unloaded host foreground delivery measures 1.75 seconds for the first sample
  and 0.77 to 0.90 seconds for three following samples, about 25 ms clock
  uncertainty. A prior loaded-host cold run took 8.4 seconds and is retained
  in evidence. The two-second target is not a background-delivery guarantee.
- Original primary shopping tables, five preferences and both private-vault
  files match exactly after restoration, with 28 active rows, original display
  and network. Only the new empty WorkManager infrastructure database is added;
  no synthetic work remains. Disposable relay/PG/runtime credentials and the
  isolated release app/fixture are removed. The clean temporary second emulator
  is retained for upcoming S5 local recovery checks. Physical phone untouched.
- Staged and history Docker secret scans found no leaks. No cluster command,
  deployment, remote or push. A new authorized physical sharing pass, extended
  offline use, cluster restore/resource drills and week-long pilot remain gates.

Evidence under `/private/tmp`: `usuals-s4-completion-full-gate.log`,
`usuals-s4-harness-lifecycle-gate.log`, `usuals-s4-final-current-server-gate.log`,
`usuals-s4-final-results.json`, `usuals-s4-final-artifact-checks.log`,
`usuals-s4-final-offline-finish-retry.log`, `usuals-s4-warm-final-latency.log`,
`usuals-s4-final-clean-barcode-ui.log`, `usuals-s4-final-edited-row-ui.log`,
`usuals-s4-final-offline-reboot-retry.log`, `usuals-s4-final-retention-ui.log`,
`usuals-s4-final-vault-device.log`, `usuals-s4-final-release-crypto-ui.log` and
`usuals-s4-final-primary-restoration-verified.log`.

Debug SHA256: `0549e0a2ed8555afb26a1120545c890edee7130e0ddbdc0add8f5eb7ac771cce`.
Release SHA256: `2e9e72ddd732525b0a39444e07e7c716c109fd150be50eed01ebeb62e4001414`.

## Shared-list S5 local operations and recovery (2026-10-16)

Implemented locally from f4cccaa on feat/smart-list. No schema change, remote,
push, cluster access, deployment, DNS change or backup-job installation. The
phone was not used; Vito explicitly kept this pass on emulators.

- Final Docker root gate: `:app:assembleDebug :app:assembleRelease
  :app:assembleDebugAndroidTest test lint -PrecipeLive=true --no-watch-fs`,
  /private/tmp/usuals-s5-final-full-gate.log. Passes 812 tests, no failures/errors,
  three OFF skips. The full implementation gate took 10m 17s; final QA-helper-only
  recheck 1m 27s reused unchanged checks. Lint 0 errors, 94 existing warnings, 11 infos.
- Isolated PostgreSQL server gate: /private/tmp/usuals-s5-final-exact-server-gate.log,
  pass 69 tests: 26 relay, 25 sync and 18 model, zero failures/errors/skips. Includes
  frozen authority/bootstrap invalidation, missing metadata, protected grants,
  no-overwrite export, exact pins/forks/trailing frames, unreadable-vault kit
  preview without writes, held anonymous body vs authenticated catch-up and
  explicitly trusted ingress clients/spoof rejection and normal-startup rejection
  of newer/modified schema ledgers without implicit migration. Restore preflight
  rejects schema/receipt-identity mismatches before changing either boundary.
- Independent bounded protocol/operations review: zero remaining blockers.
  [Review closures](sync/review.md) are separate from executed verification.
- Both final APKs pass ELF/ZIP/SDK 16 KB checks for 12 libraries/four ABIs, bundled
  font/license checks and unchanged locale parity. Debug SHA256:
  afa1867277598872d21ed0d9911b31a2dfb1cf63c909d439ca1caa867d9a9aef.
  Optimized release SHA256:
  bea72434a68229f0e992b85d58af3d4f0aeeadb45475fd5649d7df8548bdd15c.
  /private/tmp/usuals-s5-final-results.json and -final-artifact-checks.log.
- Actual API 37.1/16 KB Keystore tests pass 4 cases:
  /private/tmp/usuals-s5-final-vault-device.log. API 26/33 remains Robolectric
  coverage, not a claimed physical/API26 hardware pass.
- Actual emulator owner share/history/Finish and later edits fed a consistent
  database dump. Restored into fresh local PG with the escrowed receipt identity
  and an absent fence file; explicit restore-fence created/applied a new fence
  before listener startup. One household frozen, zero unrevoked sessions and
  zero bootstrap tokens: /private/tmp/usuals-s5-native-fence-validation.log.
- Two real Linux-container operator grants/export commands passed exact verified
  fingerprints and produced only signed ciphertext. Native peer SAF request
  contained no private key/secret; a damaged archive was rejected before save;
  a valid matching kit previewed 1 current item / 1 purchase and saved a new local
  scope. Every existing peer shopping row and wrapped sync file matched before/
  after. /private/tmp/usuals-s5-native-peer-request.log, -peer-copy.log,
  -peer-retention.log and -peer-ui-lifecycle.log.
- Surviving owner preview used wrapped keys offline, showed both older and
  unverifiable-ancestry warnings, and saved exact backup facts under the chosen
  copy name. All existing shopping rows, the newer confirmed row and exactly 1
  unacknowledged shared intent remained; imported copy had no cursor/outbox.
  /private/tmp/usuals-s5-native-owner-copy.log, -owner-retention.log and
  -owner-ui-lifecycle.log. Owner pre-copy tar contains Room only, so no claim of
  owner pre/post wrapped-file byte comparison; that comparison passed on peer.
- Optimized release verified real kit/export AES-GCM/signatures/HPKE/framing,
  saved through SAF review, reopened after process restart and displayed the
  historical1-litre purchase. Sensitive clipboard was cleared; no key in argv.
  /private/tmp/usuals-s5-final-release-recovery-verified.log and
  -native-release-peer-clipboard-lifecycle.log. Two QA label assertions were
  corrected (Dismiss and history quantity suffix), not app bugs. Primary
  emulator stalled during the first release setup, with no current app crash;
  the clean peer completed the check. Primary cold-restarted without loading
  an old snapshot; this is not a physical performance certification.
- Local native base/WAL rehearsal verified the base and recovered a commit made
  afterward; identical archive retry succeeded and conflicting content failed.
  Measured local archive 0.779s / restore 1.577s. No off-site/cluster guarantee:
  /private/tmp/usuals-s5-local-backup-drill-retry.log.
- `server/scripts/check-manifests` passes 19 rendered/operator resources, pod
  hardening, same relay image across jobs/deployment, private metrics and no
  rendered secrets. No Kubernetes API/schema/admission/CNI/live-storage claim.
- Final hardened container smoke: read-only rootfs/receipt mount, non-root PG,
  restricted app/replication roles, 512 MiB relay limit, sampled peak 292.14 MiB,
  32 anonymous bulk requests, no OOM and no public metrics. Not authenticated
  maximum-household/cluster capacity. /private/tmp/usuals-s5-final-exact-container-profile.log.
  Local image id sha256:089745cf278bb84018466ecf8b6ffb43fa500cb8b979ef44460d926cca07b44e.
- Original primary shopping tables, five preferences and private vault restored
  byte-exact,28 active Home rows, display/network restored, no synthetic worker
  scheduled: /private/tmp/usuals-s5-final-primary-restoration.log. Temporary
  peer/runtime/PG and plaintext synthetic credentials removed. The primary
  emulator and protected original-data backups remain. Credential scans pass
  staged changes and44 prior commits; final staged scan repeated before commit.

The [deployment package](../deploy/README.md) and [runbook](sync/runbook.md)
are ready for review. Actual deployment needs Vito's explicit approval, live
TLS/CNI/storage/ingress inventory, encrypted off-cluster RPO/RTO and a fresh
physical pass when authorized. Worst-case authenticated capacity, reviewed
retention automation and a two-person week with no unexplained loss/duplication
remain gates before wider invitations. S5's local preparation is complete;
its live pilot acceptance cannot be claimed from local tests.


## UI translations and contributor workflow (2026-10-16)

- Added Spanish, Italian, French and German: 416 resource keys in 13 files per
  language, covering list/edit/history, Settings, recipes, barcode/product
  information, widgets, sharing and recovery. English remains the default.
  No input-language, schema, dependency, service wiring or server changes.
- `scripts/new-translation` creates only a new locale’s translatable XML and
  refuses existing destinations and invalid path/locale arguments. The guide
  and glossary are in [translating.md](translating.md). Runtime/structural tests
  discover locales without a registry. A temporary PT scaffold passed all
  eight focused tests and appeared in generated locale config, then was removed.
- Checks reject missing or extra entries, blank text and duplicate definitions.
  They preserve argument positions/types and cover plural `other`, arrays,
  regional fallback, escaped
  paragraphs and 0/1/2/million counts. The stale-item prompt now includes the
  count in every form; ES/IT/FR include `many`. Fixed the English removal-help
  paragraph separator being collapsed by Android.
- Final Docker gate: debug/release/test APKs, `test lint -PrecipeLive=true
  --no-watch-fs`, /private/tmp/usuals-translations-release-gate.log. Passes
  820 tests, zero failures/errors, three opt-in OFF skips; lint zero errors,
  94 existing warnings and 11 infos. Both APKs pass ELF/ZIP/SDK 16 KB alignment
  and bundled font/license checks. Generated locale lists contain exactly
  en-US, de, es, fr and it. /private/tmp/usuals-translations-artifacts.json and
  -artifact-check.log record counts and hashes.
- Native emulator checks pass in all four languages: list/count, Settings,
  recipe, sharing/recovery entry and scanner. Android’s per-app picker lists
  all five languages; German Settings and French recipe controls remain
  readable at 1.3 font scale. Protected sharing screenshots remain blocked;
  their translated controls were checked through UI semantics. No optical
  scan or live import is claimed by this resource-only pass.
- Original automatic app locale, 1.0 font scale and 28-row list are retained.
  Exact final debug APK installed and checksum verified on emulator-5554.
  Evidence: /private/tmp/usuals-translations-final-native.log,
  -native-extra.log and -final-emulator-state.log. Phone untouched. No remote,
  push, cluster access or deployment. Final staged secret scan passes.

Debug SHA256: `7a0ab171c369ea31982cd1be87d641594025355230f5707f7fe946ace52c5ce3`.
Release SHA256: `9d2fcc814b91c7b83c07cdce566645953c934eb0272afec78ef225bc24984422`.

## Shopping tools and history additions, 2026-10-18

- Full wrapper gate: /private/tmp/usuals-shopping-exact-final-gate.log, debug,
  release and Android-test APKs, test/lint with live recipes enabled. Active
  modules: 778 tests, zero failures/errors, three opt-in OFF skips. This count
  excludes 60 stale reports in the deleted shop module; older totals included
  those artifacts. Lint: zero errors, 94 existing warnings, 11 information notes.
- Both APKs pass ELF/ZIP and SDK16K alignment; font bytes and OFL asset present.
  Debug SHA256 1e74f19892b4562ccaad19ed6d1163363fee7d2cce93a08967988d1c22a941de.
  Release SHA256 00f4a9ef7dd53ff1be371054b6573c76aab4a03e97382e1adf81e660135b7973.
- Native emulator passes favourite pin/defer/restore, mode restart/hide controls,
  Finish, searched/selected rebuy with quantity preserved, favourite deduplication
  and saved category ordering/awake/haptic preferences. Logs:
  /private/tmp/usuals-shopping-native-final.log and -native-history.log; protected
  screenshots /private/tmp/usuals-shopping-qa. Baseline restoration is recorded
  in /private/tmp/usuals-shopping-restoration.log. Phone untouched. No push or
  cluster action. No new optical barcode or live voice pass claimed.

## Weekly reminders, 2026-10-18

- Off by default; weekday/time controls, permission denial/settings path and
  consent stay on the device. ServiceLocator wires the durable controller and
  real WorkManager adapter. Tokens/due times reject stale and duplicated work.
- Exact Docker full gate /private/tmp/usuals-reminders-final-gate.log passes
  debug/release/test APKs, tests and lint: 786 active-module tests, zero failures
  or errors, three OFF skips; lint zero errors, 94 existing warnings, 11 infos.
  /private/tmp/usuals-reminders-artifacts.json records both APK hashes, fonts,
  license and ELF/ZIP/SDK16K checks.
- Native opt-in/denial, Sunday10:30 selection and process restart pass. A
  synthetic due-time fixture exercises the real worker, one generic notification,
  tap-to-open and disabling. Evidence: /private/tmp/usuals-reminders-native-final.log,
  -native-time.log and -worker-native.log. No elapsed week or guaranteed exact
  delivery claim. An emulator input stall required a cold boot without snapshots
  or wiping; final native checks completed afterwards. Original app state,
  notification permission flags and animation scales are restored, latest APK
  retained. /private/tmp/usuals-reminders-restoration.log. Phone untouched.

## Italian, French and German input, 2026-10-18

- Three automatically discovered UTF-8 packs add quantities, capture grammar,
  all category seeds, pantry staples and unsupported recipe measures. Compound
  measures match the longest data entry; spoken quantities remain in source notes
  instead of becoming invented piece counts. Existing identities, numeric/unit
  mappings and normalization fields are covered by regressions and unchanged.
- Docker /private/tmp/usuals-input-full-gate.log passes both variants, test APK,
  all tests/live recipes and lint: 790 active-module tests, zero failures/errors,
  three OFF skips; lint zero errors, 94 existing warnings, 11 infos. Both APKs
  contain all five packs and pass 16K/font/license checks. Exact hashes are in
  /private/tmp/usuals-input-artifacts.json. Focused parser/recipe/category gate
  also passed, including compound French/Italian measures and English word counts.
- Final native mixed-language share capture creates six correctly named rows
  under dairy/bakery and preserves three2-litre quantities in English UI.
  /private/tmp/usuals-input-native-final.log and input-languages.png record the
  check; original database, preferences, private state and animation settings are
  restored exactly. Final debug APK retained. Earlier attempts stalled during
  the Docker build; final QA ran without a concurrent build. Phone untouched.
  No cluster action, remote or push.

## Local deployment preparation, 2026-10-18

- Isolated `server/scripts/test-local` passes 69 tests with real disposable
  PostgreSQL, zero failures/errors/skips, and builds the distribution through
  `scripts/dgradle`. Evidence: /private/tmp/usuals-deployment-server-gate.log.
  Android sources are unchanged from the final 790-test input-language gate.
- `check-manifests` passes all 19 resources. Candidate/configured rendering,
  separate operator jobs, no rendered secrets, eight invalid inventory cases,
  platform manifest/index checks, corruption rejection and optimized-Python
  rejection pass. Evidence: /private/tmp/usuals-deployment-manifests.log and
  -validation.log. Independent bounded source review closes all findings.
- Final amd64 resource profile passes: 512 MiB/one CPU, sampled peak 471.8 MiB,
  no OOM, read-only identity, non-root PostgreSQL and restricted app/backup roles.
  A 10,480,504-byte authenticated snapshot publishes and downloads exactly.
  Eight concurrent authenticated bulk requests produce one success/seven
  admission rejections; 24 catch-up requests produce 22 successes/two rejections,
  followed by successful final catch-up. Public metrics remain absent.
  /private/tmp/usuals-deployment-profile-verified.log records the synthetic
  single-household check; it does not establish long-duration/cluster capacity.
- Local amd64 base/WAL restore passes manifest verification, post-base commit
  recovery and conflicting-archive rejection. Local archive 0.722 seconds and
  restore 1.117 seconds are Docker fixture timings, not off-site RPO/RTO evidence.
  /private/tmp/usuals-deployment-drill-final.log. Fixed the drill readiness race
  by waiting for the final TCP listener instead of the temporary Unix-only
  initialization server. Temporary databases, volumes and credentials are removed.
- The bundle builder requires a clean committed source and a new private output
  directory. It saves both explicitly selected amd64 images, validated rendered
  manifests, separate operator jobs, source/distribution/artifact hashes and
  operator documentation. Default candidates retain the invalid proxy marker.
  Generated `bundle.json` and `--verify` provide local integrity evidence; neither
  approves deployment nor supplies an external signature. See the deployment guide.
- Final debug APK checksum matches the installed emulator artifact. Shopping and
  reminder native checks were completed in separate segments after input stalls;
  partial logs retain those stalls, and the successful history/time/worker logs
  record completion. Original app/private state and settings are restored.
  Phone untouched. No push, cluster access, image import or deployment occurred.

Live TLS/CNI/storage, verified ingress inventory, encrypted off-site RPO/RTO,
many-household/long-duration capacity, fresh authorized physical sharing and the
elapsed household pilot remain separate acceptance gates.


## Usuals Android brand and design, 2026-10-18

- Product name is Usuals. All five UI languages interpolate `app_name` for
  branded sentences. New vector/adaptive/monochrome marks and widget colours use
  the shared ivory/aubergine/champagne system with offline fonts.
- Full wrapper /private/tmp/usuals-brand-full-gate.log passes debug/release/test
  APK builds, test/live recipes and lint: 790 active tests, zero failures/errors,
  three OFF skips; lint zero errors, 94 existing warnings and 11 infos. Both APKs
  pass ELF/ZIP/SDK16K and font/license checks; /private/tmp/usuals-brand-artifacts.json.
  Debug c6df0482d2d990ba68baec0e68fe4c3b6ed7a545f6c61b5a9a805f28474069c9;
  release dbf0513a277e08e6e3d1769cc38d67d953e5f2d677942e3a09a2bdc58c96762b.
- Native emulator upgrade preserves the existing28-row list. Recipe/history/
  settings/scanner and capture controls render with the new branding. Long title,
  1.3 font scale, light/dark and shopping mode pass; screenshots are in the private
  /private/tmp/usuals-brand-qa directory and -brand-native-final.log records exact
  original database/private-state and display-setting restoration. An initial
  driver attempt missed the unfocused name field; focused retry completed.
  This design pass claims no new optical barcode/live voice result. Phone untouched.
- Browser/accounts are a separate implementation group and remain under review.
  No remote, push, cluster access or deployment occurred.

## Usuals browser and account extension, 2026-10-18

- Final locked Docker gate `/private/tmp/usuals-server-web-final-release-gate.log`
  passes 132 JVM and 90 Node tests, zero failures/errors/skips. Cross-language
  fixtures cover40 normalization and 95 reducer cases, all 38 shared scenarios,
  browser/JVM signed envelopes and Tink RAW HPKE in both directions. The Android
  brand gate and exact emulator restoration are recorded above; Android sources
  did not change in the browser/account group.
- Optional standalone accounts and Authentik use SQL002/003 migrations, protected
  operator bootstrap/reset, fixed sessions and signed-device/account binding.
  Restore fences revoke sessions and invalidate rolled-back local passwords.
  Authentication has bounded separate admission. Independent source reviews close
  all identity, browser, packaging, preview and unlock lifecycle findings.
- Actual loopback browser QA passes full-fingerprint enrollment, encrypted
  add/check/edit exchange with the production native Kotlin client, automatic
  catch-up and transient pending retry. Wrong passphrase, cancellation,
  lock/reopen/reload and all five languages pass. Dark desktop 1280px and mobile 390px
  layouts show no horizontal overflow. Mobile language switching, local account
  creation with one-use secret, session revocation, reader disable and logout pass.
  Three walkthrough findings have regressions: Window timer receiver, cancelled
  vault ownership and account creation HTTP201. No browser console errors remain.
- Packaged runtime includes only browser assets/catalogs/fonts/license and
  SQL migrations 001 to 003, without test fixtures or the test-only parse module. English is the
  default; each 189-key catalog is complete and adding a locale is resource-only.
  New backend services are wired through Main/relayModule; ServiceLocator and
  Android schema/preferences/protocol IDs remain unchanged.
- Browser limits stop without consuming work for unsupported quantities, unsafe
  integers, ordered numeric record IDs or hexadecimal positions. Android retains
  owner/recovery actions and its stronger boundary against an active asset server.
  Live Authentik provider setup and production-browser compatibility beyond the
  observed local in-app browser are not claimed.
- Deployment validation passes candidate/configured rendering, all four local/
  OIDC modes,24 invalid identity inventories, exact-host 443 egress and secret
  references only. Local base/WAL restore passes at 0.722s archive / 1.194s restore,
  including recovery of a post-base commit and conflicting-archive rejection.
  Evidence: `/private/tmp/usuals-deployment-validation.log`,
  `/private/tmp/usuals-restore-drill-final.log` and `server/build/browser-qa/`.
  Disposable preview databases/credentials are removed; viewport restored.
  Phone untouched. No remote, push, cluster access, import or deployment occurred.
- Fresh amd64 relay resource profile passes with 512 MiB and 1 CPU without OOM, sampled peak
  499.3 MiB. The 10,480,504-byte authenticated snapshot publishes, concurrent bulk
  admission sheds seven of eight requests and catch-up succeeds. The near-limit
  sample leaves little headroom: it is a sync smoke test with account sources
  disabled, not production sizing or account-enabled/many-household capacity.
  Verify adequate headroom under the intended live workload before deployment.
  Evidence: `/private/tmp/usuals-deployment-profile-final.log`.

## Open Food Facts product details (2026-10-18)

- Barcode reads now prefer the UI language, with source/English and generic-name
  fallbacks. Brand alone never becomes a product name. Scan review shows brand
  and pack size when available and links to the exact product page.
- Editing a barcoded row exposes Product details: brand, pack size, ingredients,
  Nutri-Score, NOVA, declared allergens and traces, source modification date,
  saved date, explicit refresh and an Open Food Facts contribution link.
  Saved details are shown before refresh, remain available with lookup off and
  survive a failed refresh. Refresh does not rename items or change quantities.
- Room migration 5 to 6 adds only nullable product-cache columns. Shopping-data
  archives and sync contracts are unchanged. Returned barcodes are validated on
  hits and misses; bodies are bounded to 1 MiB and late opt-out results discarded.
- Full Docker gate passed: `scripts/dgradle :app:assembleDebug
  :app:assembleRelease :app:assembleDebugAndroidTest test lint -PrecipeLive=true
  --no-watch-fs`. 808 JUnit executions, zero failures/errors, 3 deliberate OFF
  live-test skips. Lint: zero errors, 94 existing warnings, 11 information items.
  Both APKs pass ELF and ZIP 16 KB alignment checks for all 12 native libraries.
- Emulator verification passed: real schema 5 to 6 upgrade preserved every
  pre-existing table fact and all 28 active items; opt-out/manual naming;
  duplicate suppression; offline cached name offers with brand/pack context;
  cached details with lookup off; stale refresh failure retaining metadata and
  timestamp; missing-record contribution copy; real product refresh for
  3017620422003 retaining the person's custom item name.
- All five UI catalogs are complete. English, Spanish, Italian, French and German
  product sheets were checked, including German at 1.3 font scale in dark mode.
  Provider text and taxonomy values remain source data. Original private files
  were restored byte-for-byte before reopening; the repeated migration preserved
  all original facts. Device locale, display and radio settings were restored.
  Phone untouched. No remote, push, server changes or deployment.
- Evidence: `/private/tmp/usuals-off-full-gate-final.log`,
  `/private/tmp/usuals-off-native-alignment.log`, and `/private/tmp/usuals-off-qa/`
  (`build-proof.json`, `qa-result.json`, `live-result.json` and screenshots).

## One Usuals icon across surfaces (2026-10-19)

- `web/assets/mark.svg` is the source for Android's brand and launcher vectors,
  brand colours, README artwork and 512px store icon. All five exports pass
  `scripts/sync-brand-assets --check`. Widgets reuse the brand drawable;
  notification and adaptive monochrome layers reuse the launcher foreground.
- README Android screenshots were recaptured on the emulator with the public
  local demo fixture and its saved Open Food Facts record. No fresh product
  request was needed. The current list/product screenshots retain source and
  licence information and show the dotted U.
- The final APK's launcher and widget picker preview show the shared artwork
  on the emulator. No home-screen widget was added. Original emulator database,
  preferences and private files were restored byte-for-byte after the fixture
  screenshots; the temporary lookup setting was removed with them.
- Final Docker gate passed through `scripts/dgradle :app:assembleDebug
  :app:assembleRelease test lint --no-watch-fs` in 2m36s. It records 876 JUnit
  cases, zero failures/errors and five intentional live-test skips. Lint has
  zero errors, 94 existing warnings and 11 informational findings. Both APKs
  pass ELF/ZIP 16 KB alignment for all 12 native libraries.
- Debug APK SHA256:
  `1122e76723a36607c5bddb03020d735418080f76ce249f89a1cfd833f594fd42`.
  Release APK SHA256:
  `95d293fbc16da412686ec392e113d4b1d932e04c407a7ed055ef1b5e12c95e5b`.
- Authentik's existing Usuals application now references the verified web SVG
  in `meta_icon`. Only this metadata field changed; no deployment or access
  configuration changed. No source push or store publication occurred.
- Motorola Edge 40 Neo received the final debug APK with `adb install -r`.
  Its installed SHA256 matches the debug artifact above, the original
  first-install time is unchanged and MainActivity launches successfully.
  No app-data clearing or uninstall was performed.
- Evidence: `/private/tmp/usuals-brand-everywhere-qa/`, including
  `android-final-gate.log`, `build-proof.json`, `phone-install.json`, alignment
  logs and screenshots.

## First-time web household guidance (2026-10-19)

- After successful sign-in and browser vault setup, an empty browser now shows
  how to create the first household in Android, obtain the operator's one-time
  setup code, save/verify recovery, share a local list and invite this browser.
  The current origin is displayed as selectable, wrapping text. All five
  catalogs contain the complete guide, using the actual Android action names.
- Account/session access and household membership remain separate. No bootstrap
  endpoint, admission policy, owner operation or invitation check changed.
  Browser-only household creation remains outside the current implementation.
- `server/scripts/test-local` passed through `scripts/dgradle`: 132 recorded JVM
  cases and 99 Node tests, zero failures/errors/skips, installable distribution
  and bidirectional JVM/WebCrypto checks. Packaged app/CSS/catalog bytes match
  the final source files. Android sources are unchanged; the preceding full
  Android gate still applies.
- An isolated localhost relay and synthetic account show the guide at desktop
  and 390px widths, including German. A valid invitation still reaches full
  fingerprint approval by the native Kotlin owner fixture, then opens the real
  encrypted sample list. The guide disappears after joining. No browser console
  errors. Temporary viewport/tab and owned preview/database were cleaned up.
- Evidence: `/private/tmp/usuals-first-household-qa/`, with build/browser proofs,
  gate logs and screenshots. Phone/emulator data and production are untouched.
  No deployment or push. Live Authentik sign-in was reported by the user; this
  change does not claim a new live OIDC verification.


## Guided first-run tour and web-owned households (2026-10-19)

- Android has a skippable five-step tour with Settings replay. Web has a
  four-step tour with navigation replay. Completion stays local. Incoming
  Android shares, invitations and widgets defer the automatic tour, including
  consumed-intent recreation. All five UI languages are complete.
- Admitted SSO/local accounts can create their first household and initial
  encrypted list in the browser. Creation requires saving and reading back an
  Android-compatible encrypted recovery kit and its separate key. Existing
  Android lists still use invitations. Web owners can approve full fingerprints
  and remove devices with fresh list keys.
- Creation is CSRF protected, atomically account bound and bounded by durable
  rate/permanent creation quotas. Migration 004 preserves migration-3 data.
  Pending identities, ciphertext and reservations survive interruptions. Owner
  retries resolve exact committed work; safe discard preserves pins and edits.
  Signed expiry witnesses remain verifiable after request cleanup, with
  membership resolution before and after the household-locked expiry check.
  Independent security/source review closed all findings.
- Final Android gate: `scripts/dgradle :app:assembleDebug test lint
  --no-watch-fs --no-parallel --max-workers=2`, BUILD SUCCESSFUL. 880 recorded
  JUnit cases, zero failures/errors, five intentional live-test skips. Lint has
  zero errors, 94 existing warnings and 11 informational findings, with no
  tutorial findings. All 12 debug native libraries pass 16 KB alignment.
  Debug SHA256:
  `6a9111fcae704b3870a7eb524ae15f9be2d5fa25ca3bea75c0a8c07894b02430`.
- Final server gate: `server/scripts/test-local --no-daemon --no-parallel
  --max-workers=2` through `scripts/dgradle`, BUILD SUCCESSFUL. 138 recorded JVM
  cases and 119 Node tests, zero failures/errors/skips. Browser envelopes,
  HPKE packages, genesis, recovery kit and initial list snapshot pass native
  codec checks. All 24 packaged web files match source bytes.
- Emulator checks passed first run, both final actions, persisted completion,
  Skip, Settings replay, system Back, incoming-share recreation, German with
  larger text, light/dark system bars and rotation. Original private files and
  display/locale/rotation settings were restored exactly. Phone untouched.
- Disposable localhost browser/emulator verification passed actual recovery
  download/read-back, clear wrong-key rejection, web-owned creation, full native
  fingerprint approval, encrypted edits in both directions and Android checks
  visible on the web. Browser removal rotated keys: Android displayed revoked
  access, while the owner retained the list. German household/tour layouts were
  checked at 390px. The viewport and original emulator files were restored.
- A prolonged automation download timeout exposed an expired-session Unlock
  button rejection. Route it through the existing account-recovery handler;
  independent delta review is clear. Forced session expiry on the final build
  returns Unlock to sign-in with a clear message and no console errors. The final
  Docker gate passed again with the same counts. No new dependency or wire-version
  change.
- Evidence: `/private/tmp/usuals-tutorial-qa/`, including final gate logs,
  native/runtime/build proofs, restoration logs and screenshots. No push,
  cluster deployment or new live Authentik verification.


## First public Android distribution (2026-10-19)

- Static source version 2026.10.19.1 / 26101901. Android Gradle assembly is
  unsigned; offline signing verifies the separate Usuals certificate pin and
  rejects debug signing. Approved encrypted key recovery was decrypted and
  compared exactly. All four approved GitHub signing secrets are configured.
- Full Android assembly, tests and lint passed through scripts/dgradle: 880
  recorded JUnit cases, zero failures/errors and five opt-in live skips. All
  22 release-tool checks pass. No new runtime dependency was introduced.
- Server/browser gate passed through scripts/dgradle: 138 recorded JVM cases
  and 119 Node tests, no failures/errors/skips, plus native/browser crypto,
  household, recovery-kit and initial-snapshot interoperability.
- Production APK emulator checks passed first-run onboarding, keyboard capture,
  persistence across restart and same-key update, three palettes and tour replay.
  Recipe/scanner entry and camera permission were checked. No optical emulator
  scan is claimed. Pixel_10's Android services froze during camera preview and
  required a restart; the phone and existing debug installation were not used.
- Apache, OpenJDK and font notices are packaged. F-Droid's official metadata
  formatter/linter passes. Five store catalogs, screenshots, privacy policy and
  Obtainium stable-APK filtering are complete. The staged credential scan passes;
  only the exact public certificate fingerprint is allowlisted.
- Initial comparison caught stale incremental compiler outputs. Release candidates
  now start with :app:clean. Two clean builds have identical Kotlin class files
  and R8 mappings; apksigcopier signature-copy verification reproduces the signed
  APK byte for byte. All 12 native libraries pass 16 KB alignment checks.
  Signed APK SHA256:
  `c64d35319635901a59d427b791874564e77085e579aaf3b1849d5abacd6eedfc`.
- The owned production test installation was removed after QA. The existing
  debug installation remains. No phone access or cluster deployment.
- Evidence: /private/tmp/usuals-public-release/. No cluster deployment.

- Source 2968d12 and tag v2026.10.19.1 are public in vitofico/usuals. All three
  release assets passed anonymous HTTPS byte comparisons. The production pin and
  encrypted recovery backup are retained; temporary plaintext signing material
  was removed after configuring the four GitHub secrets.
- F-Droid inclusion request: https://gitlab.com/fdroid/fdroiddata/-/merge_requests/53283.
  All nine pipeline jobs passed, including an independent source build and
  signature-copy verification against the published developer binary. The request
  was subsequently closed; see the policy outcome below. Obtainium's public import
  page resolves successfully.
- An isolated Linux filesystem probe confirmed root-owned build outputs block
  runner signing and runner-owned Git checkouts need explicit container trust.
  The release workflow restores artifact ownership after Docker assembly and
  trusts only /workspace inside its disposable verifier. Its driver supports
  immutable earlier release tags. Both repaired ownership probes pass.
- The first GitHub Android job passed full tests and lint. Its server job
  exposed Linux's inability to reach a host-loopback PostgreSQL port from the
  Gradle container. The disposable database now shares an isolated Docker
  network with the test runner, with no host port. The first signing job also
  confirmed the build-output ownership failure reproduced above.
- The repaired network harness passed a forced full run: all 26 Gradle tasks
  executed, 138 JVM cases and 119 Node tests, zero failures/errors/skips. All
  22 release-tool checks also passed again. No Android runtime source changed.

- Corrected GitHub runs completed successfully: [Android/server CI](https://github.com/vitofico/usuals/actions/runs/39279639913),
  [production signing](https://github.com/vitofico/usuals/actions/runs/39279640068)
  and [CodeQL](https://github.com/vitofico/usuals/actions/runs/39279639930).
  Completed job logs are authoritative where the status API retained stale
  queued data. CodeQL has zero open alerts. The fresh runner used the configured
  signing secrets, reproduced the exact APK and verified that all three release
  assets were unchanged. All 12 native libraries pass 16 KB checks.
- GitHub Releases and Obtainium distribution are complete. F-Droid closed the
  initial request on 2026-10-19 despite all nine pipeline jobs passing. The closure
  cites the requirement for more than three months of maintenance, the merge
  request template not being followed, and insufficient unique features. Vito
  instructed us not to resubmit because the maintenance requirement is unmet.
  No replacement request was opened. Future consideration must address all
  closure reasons, not only project age. This status correction changes no APK,
  source tag, signing identity or release asset.

## Local F-Droid feedback corrections (2026-10-20)

- Prepared the [corrected local draft](../distribution/fdroid/merge-request-draft.md)
  against the current official App inclusion template. Exact headings/checklist
  wording and order are verified. Age eligibility and future CI remain unchecked.
  Added released-source evidence and qualified similar-app comparisons; acceptance
  of distinctiveness remains F-Droid's decision.
- The reference recipe pins the full source SHA of v2026.10.19.1, with matching
  tag, source version/code and signing certificate. Verified all eight linked
  source/screenshot paths at that revision. No published artifact was rebuilt.
- scripts/dgradle passed 22 release-tool checks and official fdroidserver 2.4.5
  lint --format against an isolated copy; the Docker evidence check also passed.
  Logs: /private/tmp/usuals-fdroid-feedback-20261020/. Runtime build/emulator
  evidence is reused because Android/server inputs are unchanged.
- Resubmission remains paused. Earliest reconsideration is 2027-01-20 with
  ongoing-maintenance evidence and fresh policy/release review. No GitLab write,
  replacement MR, CI trigger, automatic follow-up, push or deployment occurred.

## QR phone connection (2026-10-19 to 2026-10-20)

- The web owner can show an expiring, one-use invitation QR, copy a private link
  or use full-fingerprint approval. Android puts scanning first, displays the
  destination before connecting and checks approval while the screen is resumed.
- Reverse QR approval preserves the exact native signed assertion and checks the
  complete displayed invitation before staging membership. Independent review
  closed the missing member/secret-hash/expiry binding. Re-signed mutations,
  invalid signatures, foreign pins and committed-approval retries after expiry
  have regression coverage. No sync framing or database migration changed.
- Final Docker Android assembly, instrumentation assembly, tests and lint passed
  through scripts/dgradle: 882 recorded JUnit cases, no failures/errors, five
  intentional live-test skips and 22 release-tool checks. The affected final
  server/browser gate passed 138 JVM and 126 Node tests with no failures/skips,
  including native/browser crypto and recovery interoperability.
- The 16 KB emulator ran the production sharing factory against disposable local
  PostgreSQL/HTTP: server confirmation, reverse verification QR, automatic join
  completion and shared-list discovery. Full-fingerprint approval exercised the
  camera-free fallback. A browser-created encrypted item reached Android after
  an explicit harness sync. QR image decoding and camera cleanup use automated
  generated-frame tests; no live physical-camera scan is claimed.
- Expired pending requests retain the connection controls. Robolectric and
  emulator checks verify preview/cancel preserves the pending identity and
  explicit replacement sends one fresh request. Existing household identities
  and saved list choices have regression coverage.
- Visual QA corrected the web navigation label and dialog focus/layout. All five
  language catalogs remain complete. Original emulator data was restored and
  byte-verified; the test forwarding was removed. No phone or cluster access.
  The published Android APK and its immutable tag remain unchanged.
- Evidence: /private/tmp/usuals-qr-android-final.log,
  /private/tmp/usuals-qr-server-final.log and /private/tmp/usuals-qr-qa/.

## Approved server publication and rollout (2026-10-20)

- Vito explicitly requested pushing and deploying the new version. Published
  through `9729d7c` to `main` and `feat/smart-list` with normal fast-forwards.
  No release tag, Android asset or F-Droid request was changed.
- Runtime source `a16ca3c` reuses its successful full Docker Android/server/web
  gates and independent source review. The source-bound bundle, uploaded archive,
  imported platform manifest and running container configuration digests matched.
- A fresh pre-upgrade base backup passed `pg_verifybackup`, then base/WAL were
  streamed directly into encryption. Checksum-verified encrypted recovery files
  are retained at the previously approved operator-backup destination.
- With relay traffic stopped, the explicit migration Job applied 004, then the
  guarded image patch rolled out successfully. Identity/fence hashes and existing
  Authentik/local-account settings are unchanged. PostgreSQL configuration is
  unchanged and WAL archiving reports zero failures. The former schema003 binary
  is not a compatible image-only rollback target after this migration.
- Base checks and the updated overlay render pass. The guarded patch/migration
  and final overlay pass server-side dry-run. Live TLS/readiness, ten exact source
  assets, security/no-store headers, both auth providers, closed anonymous APIs,
  private metrics and the empty browser model-origin allowlist pass.
- Desktop and 16 KB emulator Chrome display the updated app. Authentik reaches
  its normal owner login form. Authenticated production flows require owner
  sign-in; disposable local household/QR/import test evidence remains applicable.
  No phone or owner credentials were used.
- See the [deployment record](../deploy/theficos/README.md) for source/image,
  backup and recovery details. Private evidence: `/private/tmp/usuals-upgrade-20261020/`.
