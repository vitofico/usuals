# Development

Docker is required. Use `scripts/dgradle` for every Gradle invocation:

```sh
scripts/dgradle test
scripts/dgradle :core:parse:test
scripts/dgradle :llm:test
scripts/dgradle :app:assembleDebug
scripts/dgradle lint
python3 scripts/check-native-alignment.py app/build/outputs/apk/debug/app-debug.apk
```

The Dockerfile pins the JVM and Android toolchain. The wrapper keys its image
on the Dockerfile contents and keeps Gradle and Android caches in named volumes.
No host JDK is required. Full tests take about ten minutes; keep their logs in a
local file and run them in the background during pivot verification.

The native-alignment check needs only Python 3. It inspects every packaged ABI:
ELF LOAD segments must support 16 KB pages, and uncompressed libraries must start
on 16 KB ZIP boundaries. Both APK workflows run it before uploading an artifact.
CameraX 1.4.2 replaces the 4 KB native library in 1.3.4; AGP 8.5.2 already handles
ZIP alignment. Do not suppress compatibility warnings or enable legacy packaging
to conceal an incompatible native dependency. Follow the Android
[16 KB verification guide](https://developer.android.com/guide/practices/page-sizes)
and exercise camera preview and scanning on a 16 KB emulator after native updates.

Tests use JUnit 4, Truth, `runTest` and Robolectric. Use deterministic clocks,
explicit locales and stable tie-breaking. Network clients use scripted fixtures;
live Open Food Facts contract tests run only with `USUALS_OFF_LIVE` set.
New behavior needs meaningful tests and fixes need regression coverage.

Install with host ADB, selecting the emulator explicitly when a phone is also
connected:

```sh
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n io.theficos.usuals.debug/io.theficos.usuals.MainActivity
```

For the pivot, verify each phase on the emulator, update section 8 of the local
pivot plan, then commit on `feat/smart-list`. Publication follows the maintainer
authorization and [release process](distribution.md); deployment is separate.
The debug id is `io.theficos.usuals.debug` and can coexist with a release.
Unreleased schemas 1 to 3 are destructively replaced by schema 4. Keep exported
schemas; every schema change after version 4 needs a migration and migration test.

`ServiceLocator` owns dependency construction. Check a new production class is
actually wired and exercised through the app. Pure algorithms belong in JVM
modules; UI sentences and resource formatting belong in `:app`.

Run `scripts/secret-scan` before release. CI uses the same Docker build path.
Signing credentials are supplied through environment variables and are never
committed. See ADR-0009 and ADR-0010 for distribution and reproducibility.

## Languages

English display strings live in `app/src/main/res/values/`, split by feature.
To add a UI language, add complete translated XML files in `values-xx/`.
No registry or Kotlin change is needed. `StringParityTest` checks every discovered
locale against all translatable string, plural and array keys; AGP generates
the per-app language list. `resources.properties` keeps the default at en-US.
Tests that inspect UI copy explicitly use English. The
[translation guide](translating.md) documents the contributor workflow, glossary
and `scripts/new-translation` scaffold command. Parity checks also reject blank
text, duplicate keys, broken format arguments and incomplete plural/array data.

Input languages are independent. Add an ISO-named UTF-8 properties file and
vocabulary tests under the `core/parse` input-language resources. Discovery is
automatic. The pack contains parser vocabulary, evidenced plural rules, category
seeds, recipe measures and pantry staples. See its [format notes](../core/parse/src/main/resources/input-language/README.md).
Changes to existing identity rules must bump `NORMALIZER_VERSION`; changes to
model prompts/contracts must bump `PROMPT_VERSION`.

## Local suggestion replay

Export shopping data through History, copy it into the repository's ignored
`internal/` directory, then run, for example:

```
scripts/dgradle :smart:replay -Phistory=/workspace/internal/history.json
```

The harness reads the same JSON archive as import. It walks trips in date
order, withholding each target and all future trips from training. Trips with
fewer than three strictly earlier trips are not scored. Preferences are omitted
because the archive does not contain their historical state; confirmed aliases
are applied consistently to all three predictors. It reports aggregate hits
out of six slots per held-out trip for interval-plus-staples, last-trip items
and top-six staples. Fewer than six predictions still use six slots.

Synthetic tests establish mechanics, not product accuracy. Production interval
predictions remain off. Before changing that gate, review a replay over real
history with at least eight held-out trips and require strictly more hits than
both baselines. If it does not win, keep staples and recheck after 8 to 10 weeks.
The harness prints no item names and changes neither the archive nor app data.

## Recipe verification

`RecipeLiveTest` is opt-in. `scripts/dgradle :recipe:test -PrecipeLive=true`
reads ten public pages with the production reader and checks a site that blocks
it. The normal suite performs no recipe-site requests. The URL list is under
`recipe/src/test/resources/recipe-urls.txt`. Live checks on 2026-10-15 found:

| Source and page | Structured ingredient lines |
|---|---:|
| [RecipeTin Eats, spaghetti](https://www.recipetineats.com/spaghetti-bolognese/) | 16 |
| [Budget Bytes, Cajun pasta](https://www.budgetbytes.com/one-pot-creamy-cajun-chicken-pasta/) | 17 |
| [RecipeTin Eats, beef stew](https://www.recipetineats.com/beef-stew/) | 20 |
| [Cookie and Kate, lentil soup](https://cookieandkate.com/best-lentil-soup-recipe/) | 16 |
| [Downshiftology, boiled eggs](https://downshiftology.com/recipes/perfect-soft-boiled-hard-boiled-eggs/) | 1 |
| [Sally’s Baking, banana bread](https://sallysbakingaddiction.com/best-banana-bread-recipe/) | 11 |
| [King Arthur, banana bread](https://www.kingarthurbaking.com/recipes/banana-bread-recipe) | 14 |
| [Directo al Paladar, tortilla](https://www.directoalpaladar.com/recetas-de-huevos-y-tortillas/empezando-en-la-cocina-receta-de-tortilla-de-patatas-con-cebolla/amp) | 5 |
| [BBC Good Food, pancakes](https://www.bbcgoodfood.com/recipes/easy-pancakes) | 6 |
| [Minimalist Baker, chickpea curry](https://minimalistbaker.com/coconut-red-curry-with-chickpeas/) | 17 |

The reader received a blocking page from Love and Lemons even though another
client could read it. The app surfaces that block; it does not try to work
around the site's policy. Availability and content may change. These checks
establish extraction, not the accuracy of a publisher's ingredients.

## Scan and widget checks

Scan lookup starts off. A scan adds nothing until its name is confirmed; with
lookup off the sheet asks for a name and makes no network request. Unit tests
hold ten lookups behind a gate while checking that no row exists yet, and revoke
opt-in while a request waits for a shared rate-limit permit.

Enable lookup explicitly in Settings to test a real package, confirm the offered
name and category, and check the Open Food Facts ODbL credit. In airplane mode,
an uncached scan must offer manual naming. Restore connectivity afterwards.
Add the Usuals widget from the launcher's widget picker; verify it updates
when a row is checked and opens the list when Settings was last visible.
Store tags are edited with rows and tested using the list's All stores filter.
