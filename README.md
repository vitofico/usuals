<p align="center">
  <img src="docs/assets/readme-cover.svg" alt="Usuals. Good things, remembered. Your shopping list, a little more considered." width="100%">
</p>

<h1 align="center">Usuals</h1>

<p align="center">
  <strong>A shopping list with a memory. And room for your household.</strong><br>
  Capture on Android. Remember what you buy. Share lists through your own server.
</p>

<p align="center">
  <a href="#a-little-less-to-remember">Explore</a> ·
  <a href="#your-everyday-together">Shared lists</a> ·
  <a href="#get-started">Get started</a> ·
  <a href="docs/translating.md">Translations</a> ·
  <a href="docs/README.md">Documentation</a>
</p>

<p align="center">
  <a href="LICENSE">Apache-2.0</a> · Android + Web · Offline first · No telemetry
</p>

<p align="center">
  <a href="docs/assets/android-list.png"><img src="docs/assets/android-list.png" alt="Android shopping list: groceries grouped by category, quantities, store filters and voice capture" width="42%"></a>
  &nbsp;&nbsp;&nbsp;
  <a href="docs/assets/android-product.png"><img src="docs/assets/android-product.png" alt="Product details: a real Open Food Facts lookup with ingredients, nutrition grades, declared allergens and source attribution" width="42%"></a>
</p>

<p align="center"><em>Everyday essentials. A considered design. Your data stays yours.</em></p>

## A little less to remember

Usuals remembers the shops you finish, so the next list starts with a little
more context. The Android app works on its own, without an account, a server or
a model. Warm paper, aubergine accents, serif headings and a matching dark theme
keep the list easy to read in the kitchen and at the shop.

Choose **Plum**, **Grove** or **Tide** in **Settings > Appearance**. Each palette
has a matching dark mode, follows your device and carries through to the widget.
The web editor offers the same choices from its Appearance control.

![Plum, Grove and Tide palettes, each with matching light and dark colors](docs/assets/palettes.svg)

| When you want to… | Usuals helps you… |
| --- | --- |
| **Catch what you need** | Type, dictate, share text or scan a barcode. Edit names, notes, quantities, categories and store tags. Add the home-screen widget for a quicker way back in. |
| **Remember the last shop** | Finish a shop to save checked items as dated purchases. Keep unchecked rows open, undo a finish, search history and rebuy selected items. |
| **Keep the essentials coming** | Set explicit repeat rules, pin essentials and choose from up to six suggestions. Quantity hints wait for a tap; similar names merge only with your approval. |
| **Turn a recipe into a list** | Share a recipe link, review its ingredients and choose what to add. Already-listed items are excluded; pantry staples and recent purchases start unticked. |
| **Move through the shop** | Use a focused shopping mode, store filters and aisle order. Defer an item to the next finished shop, or opt into a weekly local reminder. |
| **Keep your history** | Import a dated past list, share a list as text, or export and import shopping data through Android's file picker. |

On Android, **Favourites** in **Shopping tools** are personal shortcuts for items
you want to add again. Save an item from its editor, then tap it in Favourites
when you need it. Items already on the current list are marked. Favourites do
not add items automatically.

As you type, Usuals offers matching names from the current list's purchase
history. Use a suggestion's menu and **Don't suggest again** to hide it from
typing suggestions and the suggestion cards on this phone. You can still add the item yourself.
If it has a repeat rule, Usuals asks before stopping that rule too.

Open an item's editor to **Move or copy to another list**. Choose an active list
and whether to keep the original. The new row is unchecked and keeps its item
details. Moving between account lists confirms the destination first; if the
connection or either list changes, the original stays available.

Only your explicit repeat rules add items automatically. Inferred interval
predictions stay disabled until replay over real household history beats both
baselines. Staples, purchase memory and explicit repeats work now.

## Scan once. Know a little more.

The scanner pauses after each accepted barcode. Choose **Scan next item** when
you are ready; holding the same package in view does not fill the list with
copies.

Enable **Open Food Facts** lookup to get a suggested name and category, with
brand and pack size when available. Confirm the name once and reuse it offline.
Missing records can still be named manually.

Open **Product details** on a barcoded item for ingredients, Nutri-Score, NOVA,
declared allergens and traces. Saved details remain available offline, with a
saved date, explicit refresh and a link to view or improve the source record.
Community data can be incomplete or outdated; check the packaging for ingredients
and allergens. Product data is credited to Open Food Facts under the ODbL.

## Recipes, without the busywork

Tap **Import** to paste a note from Google Keep or another app, or open a plain
text or Markdown file. Plain lists use one item per line. Bullets, numbered lists
and checkboxes are supported. Ticked entries are imported too and arrive ticked,
so a Keep list reused from the last trip comes in whole and as it was. Turn on
**Repeated emoji set the quantity** to read `Cerveza 🍺🍺🍺` as 3 Cerveza; with it
off, emoji stay in the item name. Text is read locally, without a model. Review
the proposed items and quantities before adding; long notes are added in the
background, batch by batch, so the list stays usable.
Use a Markdown heading such as `# Weekend shopping` for a custom note title.
On Android, you can also share the note directly to Usuals for the same review.

Share a recipe URL into Usuals or select **Link** in the import screen. Structured
recipes are read directly on the phone and need no model. Review the ingredients
before adding them; Usuals preserves your choices and avoids existing list
items. Sites that block the request show an explanation.

An optional OpenAI-compatible model can read prose recipes. With image support,
it can also read photos of written lists. Photos stay local until you
request a read and its image check succeeds. Both Android and web offer a
review before anything is added. See [provider setup](docs/providers.md) for the server gateway and advanced
direct-provider options.
Sign in under **Settings > Account** and turn on **AI assistance**.
Your server supplies the provider setup; model details remain available before
use. AI is off by default and inputs are sent only after your explicit action.
The everyday list needs no AI service.

## Your everyday, together

Use Android alone, or sign in to a Usuals server that you or someone you trust
runs. Signed in, your lists live in your account on that server: the phone and
the web app show the same lists, and a change on one appears on the other within
a second or two. The phone keeps an offline copy and sends its changes when it is
back online.

To connect your phone, open **Account > Connect a phone** in the web app. On the
phone, choose **Settings > Account > Scan the QR from the web app**, or scan the
code with the camera. Confirm the server address once and the phone is connected,
with no code to compare. Without a computer at hand, enter the server address on
the phone and choose **Sign in in your browser**.

To shop together, choose **Share** on a list and add people who have an
account on the same server. Everyone added can edit the list and add others; the
owner can remove people or delete the list, and anyone else can leave it. There
are no invitations or codes to send. See the
[server connection guide](docs/server-connection.md) for the details.

<p align="center">
  <a href="docs/assets/web-editor.png"><img src="docs/assets/web-editor.png" alt="Usuals web app in dark mode: a list shared with another account, with quantities and a picked-up item" width="100%"></a>
</p>

<p align="center"><em>Android in your pocket. A larger workspace in your browser.</em></p>

| Part | What it does |
| --- | --- |
| **Android** | Offline lists, purchase history, capture and shopping tools. Signing in is optional; it syncs your lists with your account and lets you share them. |
| **Web** | Your account's lists in the browser, sharing, **Connect a phone** and connected-phone management. Local accounts or optional Authentik SSO provide sign-in. |
| **Server** | A small Kotlin/Ktor service with PostgreSQL that holds every list of its accounts, applies changes and serves the web app. Lists are encrypted at rest. You run it on your infrastructure. |

The server applies every change, so it can read the lists of its accounts, and so
can whoever operates it. Encryption at rest protects a copied database or backup,
not a running server. Lists on a phone that is not signed in never leave it. Read
the [privacy policy](PRIVACY.md), the [browser boundary](web/PROTOCOL.md) and the
[account/SSO guide](docs/sync/web-identity.md) before choosing a server.

The server and clients are implemented and tested locally. Cluster deployment,
production capacity, off-site recovery and a pilot with real users still need
their operational checks. The [deployment package](deploy/README.md) is preparation
material, not a running hosted service.

**Usuals Cloud** is planned as optional paid managed hosting. Offline and
self-hosted use remain free; billing and paid onboarding are not implemented.
The [hosting plan](docs/hosted-service-plan.md) records the work and launch gates.

## Private by default

- **No telemetry, analytics, advertising, crash reporting or Play Services.**
  Lists, local purchase history and suggestions work without a network.
- **Direct, optional reads.** Recipe requests go from the phone to the recipe
  site. Enabled barcode lookup sends the code to Open Food Facts, not your list
  or purchase history. No application server proxies either read.
- **Your model, your choice.** Recipe text reaches the configured model only
  after an explicit action. Direct-provider keys stay in Android Keystore. Personal server-AI keys are
  sent to your chosen server and stored encrypted there.
- **Speech follows Android's service.** Availability and audio processing depend
  on the selected speech service; keyboard capture works offline.
- **Portable shopping data.** Export/import and Android device transfer preserve
  shopping history. Cloud backup and secret transfer are excluded. Account
  credentials, sync state and the product cache are outside shopping exports.

See the [security model](docs/security-model.md) for the complete trust boundaries.

## Get started

The first launch includes a guided tour. Skip it whenever you like and reopen
it from **Settings > Guided tour** on Android, or **Guided tour** in the web
navigation. Its last step explains how to sign in and share.

Download the signed **[Android APK](https://github.com/vitofico/usuals/releases/latest/download/app-release.apk)** from GitHub Releases, or add
`https://github.com/vitofico/usuals` to **[Obtainium](https://obtainium.imranr.dev/)**
for updates. The [one-tap Obtainium import](https://apps.obtainium.imranr.dev/redirect?r=obtainium%3A%2F%2Fapp%2F%257B%2522id%2522%253A%2522io.theficos.usuals%2522%252C%2522url%2522%253A%2522https%253A%252F%252Fgithub.com%252Fvitofico%252Fusuals%2522%252C%2522author%2522%253A%2522vitofico%2522%252C%2522name%2522%253A%2522Usuals%2522%252C%2522additionalSettings%2522%253A%2522%257B%255C%2522includePrereleases%255C%2522%253Afalse%252C%255C%2522apkFilterRegEx%255C%2522%253A%255C%2522%255Eapp-release%255C%255C%255C%255C.apk%2524%255C%2522%252C%255C%2522verifyLatestTag%255C%2522%253Atrue%257D%2522%257D) selects only
stable production APKs. GitHub CI builds, signs and publishes updates automatically
after changes on `main` pass the Android and server checks. Usuals is not available on F-Droid; the
[initial submission was closed](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/53283)
because the project is too new and other inclusion concerns remain.
See the [distribution guide](docs/distribution.md) for signatures,
checksums and the inclusion status.
The [corrected F-Droid draft](distribution/fdroid/README.md) remains unsubmitted while
the maintenance requirement is unmet; no resubmission is scheduled.

Android works offline without an account. Syncing and shared lists are optional
and use your own compatible server. There is no managed hosting subscription in this release.
Read the [privacy policy](PRIVACY.md) before enabling external services.

To build Android, install Git and Docker, then run:

```sh
git clone https://github.com/vitofico/usuals.git
cd usuals
scripts/dgradle :app:assembleDebug test --no-watch-fs
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. With Android platform-tools
installed, select your emulator explicitly:

```sh
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
```

Every Gradle build and test uses `scripts/dgradle`. For the server's real
PostgreSQL integration suite and distribution build:

```sh
server/scripts/test-local
```

Use the [server guide](server/README.md) and [deployment runbook](deploy/README.md)
for instance preparation. Production deployment is an explicit operator action.

The [development guide](docs/development.md) covers full tests, native 16 KB
alignment, emulator checks and suggestion replay. The
[verification record](docs/release-check.md) and [known issues](docs/known-issues.md)
explain the tested scope and remaining limits.

## Make Usuals speak your language

English is the default. Spanish, Italian, French and German are included in both
the Android interface and the web editor. Input languages are independent of
the display language.

Android translations are complete `values-xx/` resource folders, with automatic
locale discovery and parity checks. The browser uses complete locale catalogs.
No registry change is needed to add a language. The
[translation guide](docs/translating.md) explains the workflow, helper script and
glossary; you can improve the copy without installing Android tooling.

## Contribute

Read the [contribution guide](CONTRIBUTING.md),
[architecture](docs/architecture.md) and [roadmap](docs/roadmap.md). Preserve
migration paths, optional network access and fast offline capture.

Maintained by [@vitofico](https://github.com/vitofico).

## License

[Apache License 2.0](LICENSE). Bundled dependencies and fonts retain their own
licenses and notices. See the [contribution agreement](CLA.md).

## Support

If Usuals is useful to you and you'd like to chip in, you can buy me a coffee:

[![Ko-fi](https://img.shields.io/badge/Ko--fi-Support-FF5E5B?logo=ko-fi&logoColor=white)](https://ko-fi.com/vito507767)
