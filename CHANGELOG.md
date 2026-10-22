# Changelog

## A calmer shopping list

- Android opens with the list name, groceries and a clearly labelled Add items
  action. List tools share one menu, suggestions open when needed, and a single
  store selector replaces the row of filters.
- Item editing starts with name and quantity. Extra details and actions unfold
  separately; Save and Cancel stay visible. Favourites are compact rows, and
  history puts purchases before data-management tools.
- The browser puts the selected list first, simplifies navigation and account
  setup, and gives everyday controls larger touch targets. Dialog actions stay
  reachable, with clear keyboard focus and wrapping long names.
- The three palettes and every existing function remain available. Labels and
  layouts work across all five languages, narrow screens and large text.

## Reuse everyday items

- Favourites explain how to save an item and add it again from Shopping tools.
  Items already on the list are marked so adding them again does not quietly
  increase their quantity.
- Suggestions while typing come from this list's purchase history. Choose
  **Don't suggest again** to hide an item from both typing suggestions and the
  suggestion cards. You can still type it yourself.
- Move or copy an item to another active list from its edit sheet. Copies arrive
  unchecked, with their quantity, note, store, barcode and category. Moving
  keeps the original whenever the destination cannot be confirmed.

## Scans ask before adding

- A scanned barcode no longer lands on the list as bare digits. The scanner
  asks "Add this product?" with the looked-up or remembered name, or asks you
  to name it, and adds nothing until you confirm. Scanning a product already
  on the list joins its row.
- Small and curved labels, such as spice jars and bottles, read more reliably:
  the camera starts at 2x zoom so the label can be held in focus, reads at a
  higher resolution, and a read that flickers between frames still counts.
  Tap the preview to focus and pinch to zoom.

## Product facts on the list

With Open Food Facts lookup on:

- The product sheet shows Nutri-Score, NOVA and Green-Score as coloured chips,
  each with its letter or number. Grades that are not stated or do not apply
  are shown as such, never as a colour. Additives are listed by E-number.
- Allergens and traces are named in your language.
- Rows show their Nutri-Score from what this phone already looked up. A
  setting turns this off.
- Items you never scanned this time (suggestions, repeats, typed staples) use
  the product your household scanned before, when there is only one. The sheet
  says which product it is based on, and nothing is recorded as a scan.
- You can pick allergens to flag. Rows and the product sheet then say
  "Contains milk" or "May contain tree nuts". The choice stays on this phone.
- Basket overview, off by default: the share of ultra-processed items and a
  count of Nutri-Score grades for the current list and for each past trip,
  shown only once enough items have product data.
- Scanning shampoo or detergent no longer fails: it is filed under Personal
  care or Household and named from Open Beauty Facts or Open Products Facts.
- Green-Score A+ and F are read correctly.

## Long lists stay fast

- Typing in the add field no longer recomputes the whole list on every key.
  Rows and suggestions are worked out in the background, and quantity hints
  read each item's purchases once instead of scanning the whole history per
  item.
- Rows on the list redraw only when they change.
- Rebuying a past shop, adding due repeats, adding several items at once and
  restoring a backup each rewrite the list once instead of once per item.
- History draws a big shop row by row as you scroll, and searching it no longer
  rebuilds its matcher for every purchase.
- Sync skips intents the server already settled, prunes them from the outbox,
  skips re-applying a list it just pushed, and keeps its status checks off the
  main thread.
- Self-hosters can turn on backup retention: with `USUALS_KEEP_BASES` set, the
  base-backup job keeps the newest base backups and the WAL they need, and removes
  the rest only after the kept ones verify.
- The relay server checks lists it produced itself by their size bounds only,
  and the strict JSON check compiles its number pattern once.

## Background import, ticked items, faster clearing

- Importing a long note no longer freezes the app. The import sheet closes as
  soon as you tap Add, and the items are written in batches in the background.
- Items ticked in the note arrive ticked in Usuals.
- Repeated emoji as a quantity (`Beer 🍺🍺🍺` adds 3 beers) is now a switch on
  the import screen, off by default. When off, emoji stay in the item name.
- Removing all items, or several selected ones, is fast on a long list.

## Long notes import

- Importing a note takes up to 500 items, up from 150, so a long Keep list comes
  in whole.
- When a note is too long, the import screen shows why at the top. The message
  used to sit below the pasted text and the keyboard, so the import looked like
  it did nothing.

## Edit an item's note

- The edit sheet, opened by holding an item, has a **Note** field. Change the
  small line shown under an item's name, add one, or clear it. The note syncs
  like the rest of the item.
- Typing still adds notes directly: `Milk (lactose free)` or `Milk for the
  cake`.

## Keep notes import whole

- Importing a note brings in every item, ticked or not. A Keep list with some
  rows ticked used to import only the unticked ones. Untick what you do not need
  in the review.
- The import screen now explains that repeated emoji after an item set its
  quantity.

## Quicker typing, empty a list, Keep imports

- While typing an item, a tap anywhere outside the field closes it and the
  keyboard, so the list and its menus are reachable again. The tap does not
  also check the item under it.
- **Remove all items** in a list's menu empties the list after a confirmation,
  for everyone who has it. Purchase history stays, so the usuals are still
  suggested.
- A Google Keep note imports even when every item in it is ticked, which is how
  a list reused from the last trip looks. Checked items are still skipped when
  the note also has unticked ones.
- Repeated emoji after an item become its quantity: `Cerveza 🍺🍺🍺` imports as
  3 Cerveza. A single or mixed emoji is dropped from the name.

## Lists that follow you, and sharing by name

- Signed in to a Usuals server, your lists now live in your account there. The
  phone and the web app show the same lists, and a change on one appears on the
  other within a second or two. The phone keeps working offline and sends its
  changes when it is back online.
- Share a list by choosing **Share** and adding people who have an account on the
  same server. Everyone added can edit the list and add others; the owner can
  remove people or delete the list, and anyone else can **Leave list**. Sharing
  needs no household, invitation, code or recovery file any more.
- **Archive** a list from its menu to move it out of your lists without deleting
  it. The owner archives it for everyone who has access, and only the owner can
  restore it; anyone else archives it just for themself. Archived lists wait in
  an **Archived** section, on the phone's list picker and below the list on the
  web, with **Restore**, and they keep syncing.
- Connect a phone by scanning the QR from **Account > Connect a phone** in the web
  app. The phone asks once to confirm the server and connects, with no code to
  compare. **Connected phones** in the web app lists them, each with
  **Disconnect**. On Android, everything lives under **Settings > Account**.
- Updating keeps every list on the phone, old shared lists included, as ordinary
  lists. A phone that was already connected uploads all its lists on its next
  sync. If two phones held the same old shared list, the second becomes a
  separate copy that can be shared again.
- The web app no longer has a vault or passphrase. It deletes the old browser
  storage when it loads, so lists that existed only in a browser are gone.
- Privacy changes: the server can now read the lists it holds, and so can
  whoever runs it. Stored lists are encrypted with a server key, which protects a
  copied database or backup but not the running server. Lists on a phone that is
  not signed in still never leave it. When sharing, accounts on the same server
  see each other's names and email addresses.
- The web app stays signed in for 30 days after you last use it, and a phone for
  180 days.
- Older app versions are asked to update when they reach an updated server.
  Server migration 008 replaces the household tables and needs a new list key
  file; see the [runbook](docs/sync/runbook.md).

## Connect your phone from the computer

- A signed-in computer's **Connect a phone** shows one QR. The phone scans it,
  the computer shows the phone's six-digit code, and one approval there signs
  the phone in and, for an owner browser, adds it to the household. The phone no
  longer needs its own browser sign-in, which failed when the server was open
  only on the computer.
- **Invite someone** is now the separate action for another person's phone or a
  browser.
- Server migration 007 stores connection offers. Older app versions keep the
  address-and-sign-in path.

## Fewer browser unlocks

- The browser no longer locks when you switch tabs or apps. It locks when the
  page closes or after 30 minutes without activity, instead of five.
- On a computer you trust, tick **Keep this browser unlocked** when unlocking.
  The browser then opens your lists without the passphrase while you are signed
  in, like the Android app. **Forget this browser** in Account, or signing out,
  turns it off.

## Browser invites survive sign-in

- Joining from the server's invite page in a browser no longer needs the link
  opened twice: the tab keeps the invite through sign-in and unlock for up to
  30 minutes, then forgets it once used or closed. The invite key never leaves
  the browser.

## One-scan pairing and shared lists

- Put server connection, household creation and invitations on one Android
  Sharing screen that shows only the current step. Settings shows one Server
  and sharing row.
- Sign in through the browser with matching six-digit codes. The household is
  drafted as soon as the browser approves, including after Return to Usuals.
- Save the recovery file once: the app writes it, reads it back and checks it.
  Copy the separate key and confirm before creating.
- Invite with one QR code or link. The other phone scans it in Usuals or with
  the camera through the server's join page, both phones show the same six
  digits, and the owner approves with one tap. Invites work once and expire
  after 24 hours; the relay cannot extend them.
- Stop waiting on a denied or expired join. Browser joiners reopen the invite
  link after signing in.
- Server migration 006 stores invite descriptors and join proofs. Older app
  versions keep existing households and report that an update is needed for
  new invites.

## Copied notes and Markdown lists

- Put text and Markdown first under a visible Import action on Android.
- Read plain lines, copied bullets and standalone checkboxes locally on Android
  and web. Skip completed entries and recognize localized recipe sections.
- Review shared Android notes before adding. Only a standalone shared recipe URL
  fetches a page; links embedded in notes stay local.
- Keep quantities, duplicate exclusion and explicit confirmation, with complete
  interface copy in all five languages.

## Automatic Android releases

- Publish signed stable APKs from GitHub CI after main passes Android and server
  checks, with automatic CalVer, increasing Android codes and localized notes.
- Require a separate clean build, pinned production certificate, 16 KB alignment
  and byte-for-byte signature-copy reproduction before publishing source refs.
- Handle concurrent main updates with fresh CI, and reuse immutable versions and
  APK bytes on recovery. Obtainium follows the resulting release automatically.

## 2026.10.20.1: QR connection and written-list import

- Import Markdown shopping lists and recipe ingredients locally on Android and web.
- Read selected or captured photos only with a connected model that passes an image
  compatibility check. Remove source metadata, bound files and model replies, and
  count each Android request against the existing daily allowance.
- Review proposals before adding, exclude existing identities, and save browser
  selections atomically. Provide complete English, Spanish, Italian, French and
  German copy. Browser models require an explicit operator origin allowlist.

### QR connection

- Connect an Android phone from the web app with an expiring invitation QR.
  Scan the phone’s verification QR back in the browser to approve its exact
  signed device keys without typing a server address or fingerprint.
- Put scanning first in Android sharing settings, show the destination before
  connecting and check approval automatically while that screen is open.
- Allow a fresh invitation after an expired request, with explicit confirmation
  before replacing a pending connection. Saved lists and existing connections stay.
- Keep private link and full-fingerprint fallbacks, local camera processing,
  existing encrypted membership and complete copy in all five languages.

## 2026.10.19.1: first public Android release

- Publish a production-signed APK with pinned certificate, source identity and checksum.
- Add Obtainium import configuration and a reproducible F-Droid inclusion recipe.
- Complete store descriptions and changelogs in all five UI languages.
- Separate unsigned builds from offline signing; reject missing keys, debug
  certificates, source/version mismatches and replacement of published bytes.
- Commit versions before tagging and require a clean rebuild to reproduce the APK.


### Web-first households (included in 2026.10.19.1)

- Create a household and its initial encrypted list in the web app, using an
  admitted SSO or local account. Save and verify an Android-compatible recovery
  kit first. Keep the existing Android-list invitation path.
- Invite, approve full device fingerprints and remove devices as a web owner.
  Persist exact pending work, rotate keys on membership changes and recover
  safely from interrupted or obsolete setup.
- Add an account-bound, CSRF-protected creation endpoint and append-only server
  migration, with durable attempt limits and per-account creation quotas.

### Guided first-run tour (included in 2026.10.19.1)

- Introduce the Android list, capture, recipes/barcodes, shopping history and
  optional sharing through a skippable five-step tour. Replay it from Settings.
- Add a four-step web tour covering household setup, account access, browser
  passphrases and everyday shared editing. Replay it from Guided tour.
- Remember completion locally, with complete English, Spanish, Italian, French
  and German copy. The tour changes no permissions, product-lookup consent or
  household membership.

### First household setup (included in 2026.10.19.1)

- Explain the first-household setup after web sign-in, with Android steps and
  the current server address above the invitation form in all five languages.
- Keep the existing invitation and full-fingerprint approval flow for browsers.

### Consistent app icon (included in 2026.10.19.1)

- Matched Android's launcher, themed icon, list header and empty state to the
  web version's dotted U, with the original aubergine and champagne colours.
- Removed the old basket mark and separate monochrome drawable.
- Applied the same mark to widgets, store metadata and refreshed documentation
  screenshots. Added canonical SVG exports to keep the artwork consistent.

### Selectable palettes (included in 2026.10.19.1)

- Added Grove, with botanical greens and oat tones, and Tide, with ink blue and
  sea glass, alongside the original Plum appearance. All three have light/dark
  variants and matching system bars, widget colors and browser styling.
- Added immediate, persistent appearance controls with complete English,
  Spanish, Italian, French and German copy. Theme preferences remain local and
  independent of shopping data, accounts and privacy choices.
- Verified contrast, preference fallback, widget rendering, emulator selection
  and restart, larger text, and browser keyboard/mobile layouts. See the
  [verification record](docs/release-check.md#selectable-palettes-2026-10-18).

### Smart shopping list (included in 2026.10.19.1)

The original pivot was implemented and verified locally on `feat/smart-list`.
Private source publication followed on 2026-10-18 and the first public signed
Android release on 2026-10-19. F-Droid inclusion remains paused. See
[release checks](docs/release-check.md) and [known issues](docs/known-issues.md).

- Named the product Usuals and refined Android with an ivory/aubergine palette,
  editorial headings, restrained champagne details and the dotted U icon.
- Added an encrypted Usuals browser editor with complete English, Spanish,
  Italian, French and German catalogs, owner-verified joining, durable pending
  edits and JVM/browser protocol fixtures. Owner/recovery actions stay in Android.
- Added controlled standalone web accounts and optional Authentik OIDC, fixed
  sessions, account/session administration and explicit operator bootstrap/reset.
  Web accounts do not grant household keys. Deployment preparation supports
  either account source with opt-in secret references and restricted IdP egress.
- Added a local-only server deployment bundle builder/verifier, validated
  nonsecret inventory and separate identity/migration jobs. Bundles contain both
  amd64 images, rendered manifests, source/distribution/artifact hashes and
  recovery/runbook documentation. Added authenticated near-limit snapshot stress
  to the disposable resource profile; live deployment remains gated.
- Added Italian, French and German input packs for numbers, units, capture,
  ingredient notes, pantry staples and categories. Packs load automatically,
  independently of UI locale. Existing normalization identity rules stay unchanged.
- Added opt-in weekly local reminders with a chosen weekday/time, Android
  notification permission, generic lock-screen content and durable duplicate/
  stale-work protection. No server or Play Services. Device consent is never
  restored by shopping archives or device transfer.
- Added shopping mode with larger rows and optional screen-awake/haptics,
  per-store category order, pinned essentials and personal buy-next-time deferral.
  Added accent-insensitive history search and selected rebuy that skips open
  identities without increasing their quantities. Shopping archive version 3
  includes favourites/layouts and retains imports of versions 1 and 2.
- Redesigned the app with bundled Manrope typography, violet actions and
  soft accent surfaces, colourful category cards, visible check circles, quantity badges,
  category icons and a keyboard/voice/scan dock. Updated recipe, scanner, history,
  Settings, widget and launcher surfaces. Settings sections expand in place;
  dated history entry opens in a focused sheet.
- Refined the design with an open header, quieter quantities and counts, inset
  grocery dividers and less visual weight in the capture dock and widget.
  Added a shared-list server plan for the existing cluster, followed by S0 below.
- Defined the shared-list S0 protocol, security/recovery boundaries, OpenAPI,
  schema-5 migration design and cluster inventory. Added test-only framing and
  Tink primitive probes plus semantic/security fixtures for later phases.
  An independent review closed seven contract findings. Local implementation
  is authorized; cluster deployment still requires approval.
- Added named local lists and selection, preserving every finalized schema-4
  record through a tested schema-5 migration. Added the pure `:sync` reducer,
  atomic source intents/outbox, contiguous catch-up and pending/conflict review.
  Archive version 2 transfers scoped shopping data while retaining version-1
  import; shared exports become independent local copies. Repeat-cycle lineage
  preserves next-shop deferral and durable Undo without replay resurrection.
- Added an isolated local Ktor/PostgreSQL relay with signed authentication,
  recipient-scoped key packages, membership CAS, restricted recovery, durable
  encrypted operations/receipts and authorized WebSocket hints. Strict wire
  parsing and PostgreSQL denial/concurrency/restart tests cover the reviewed
  contract. Cluster deployment remains a separate gate.
- Added Android encrypted sharing, Keystore protection, QR/fingerprint verification,
  membership rotation/removal, explicit ownership acceptance, durable retry,
  foreground hints, scheduled background delivery and exact conflict/Undo review.
- Added offline frozen-backup export and on-device recovery review into a fresh
  local scope. Recovery preserves existing lists, pending edits and private pins;
  old restored household authority remains permanently frozen. Added local-only
  deployment manifests, private aggregate metrics, WAL/base-backup tools and
  restore/rollback/pilot runbooks. Cluster deployment and live pilot are gated.
- Removed retailer integration, product matching, proposals and spending controls.
  Added pure JVM `:smart` and `:recipe`; reduced the model module to `:llm`.
- Finalized schema 4 with trips, item-derived purchases, check timestamps,
  preferences and confirmed aliases. Only unreleased schemas 1 to 3 may be wiped;
  future changes require migrations and tests.
- Added Finish shop and Undo, stale-check and carry-over prompts, swipe removal,
  text sharing, dated past-list import and complete shopping-data archives through
  the system file picker. Device transfer is enabled; cloud backup and secret
  transfer are excluded.
- Added complete Spanish, Italian, French and German UI translations, a
  contributor guide/glossary and a safe new-locale XML scaffold. Translation
  checks preserve placeholders, plurals and arrays and reject blanks/duplicates.
- Made English the default UI with automatic locale discovery and complete-key
  parity checks. English/Spanish input grammar and category seeds are data packs.
  Structured model failures and all display sentences are rendered in `:app`.
- Added sticky categories, history autocomplete, row editing and explicit repeats.
  Due rules add marked quantity-free rows. Removal snoozes them until the next
  trip; Undo retracts unchanged automatic additions caused by the undone close.
- Added up to six suggestions with Add, Not now and Never. Staples ship now;
  intervals remain gated off pending a win over both baselines on real history.
  Added the chronological replay command, tapped quantity hints, offered repeat
  rules and confirmed similar-name merges.
- Added on-device Recipe JSON-LD reads and ingredient-line parsing with reviewed
  deduplication. Existing list items are excluded, pantry/recent items unticked.
  Prose pages offer the optional model; untrusted text is a JSON string value.
- Fixed the saved model ceiling and persistent UTC-day usage, including probes.
  Zero prevents calls immediately and after restart. Ollama extraction/probes
  request no thinking to preserve the bounded final-answer allowance.
- Added off-by-default Open Food Facts naming with explicit confirmation,
  taxonomy categories, ODbL credit and a privacy toggle. Rapid scans insert before
  queued reads. Opt-out gates pending requests and stale settings writes.
  Offline scans offer manual naming. Product data remains outside JSON exports.
- Added store tags and list filters, plus a local home-screen widget showing
  unchecked rows and opening the living list. No polling or network job is used.
- Corrected privacy copy for system speech-service fallback and user-labelled
  local model endpoints.
- Updated CameraX to 1.4.2 for native 16 KB page support. Added an APK check for
  ELF and ZIP alignment in every packaged ABI, enforced in both APK workflows.
- Clipped camera preview to its allocated bounds so it cannot cover barcode
  entry. Verified on the 16 KB emulator and a Motorola Android 15 phone.
- Kept the display awake while the barcode camera is visible, so aiming a
  product does not time out the screen. Closing the scanner restores normal sleep.
- Required three matching camera frames before accepting a barcode, rejecting
  isolated checksum-valid misreads observed on the phone.
- Paused the camera after each accepted barcode, ignored session duplicates and
  exposed opt-in product lookup and immediate name confirmation in the scanner.
  Lookup explains missing names and can retry without adding another row.
  Confirmed names are reused offline after finishing or removing an item.
- Made recipe page reads cancellable from Close. Cancellation stops the HTTP
  request, including an in-progress body download, without changing the list.
- Reconciled product/security docs and ADRs. Retired decisions are explicitly
  historical. Final checks and native/emulator limits are recorded separately.

Earlier entries below describe historical builds.


Usuals releases are tagged [CalVer](https://calver.org) (`vYYYY.MM.DD.N`,
matching `VERSION_NAME` in `gradle.properties`). The version comes from the
calendar and the CI run number rather than from the tags that already exist,
because deriving the next version from the tag list and tagging first is how a
failed retry mints three versions and zero artefacts. CI uploads the build
before it pushes the tag, and the signed release APK is then built from that tag
itself, so what anyone installs matches what the tag points at. Notable changes
land here; the granular record is the git history, and this file, not the tag
list, is the release record.

## Unreleased: current state (2026-09)

The first cut. **Nothing has been released:** no tag, no APK, no store listing.
Everything below is built and JVM-tested unless it is marked otherwise, and the
marks are the honest part of this entry: where a seam exists with nothing
plugged into it, the bullet says so rather than counting the seam as the
feature.

### The list

- **Local-first list**, persisted in Room. Items carry a quantity, a note, a
  position, who added them, and a checked flag. A local write never fails and
  never waits on a network, and airplane mode changes nothing about the list.
- **Voice capture** through `SpeechRecognizer`, on-device where the platform
  provides it, falling back to the keyboard in the same row rather than a modal
  when it does not.
- **A rule-based utterance parser.** "dos litros de leche", "añade tomates",
  "3 x pan" and the common English shapes resolve with no network and no tokens.
  Messy input is left for a model rung rather than guessed at.
- **Normalization** as a single deterministic function: case folding under the
  root locale, accent stripping that keeps ñ, punctuation collapse, filler-word
  removal, and singularization of the common Spanish plurals. This is the cache
  key, so it is rule-based on purpose and stamped with `MATCH_VERSION`.
- **Share-sheet capture.** Usuals registers as a text share target, and shared
  text runs through the same rule parser as voice and the keyboard, once per
  share and not again on a rotation or a process restart. Pulling the
  ingredients out of a shared recipe is a model's job and is not built, so a
  paragraph of prose yields whatever the rules can find in it.
- **Barcode decoding** with CameraX and ZXing, in `:capture`. EAN-13, UPC-A and
  EAN-8 are accepted, the check digit is validated, and everything is
  canonicalized to one stored EAN-13 form. ZXing rather than ML Kit, so the app
  carries no Play Services dependency. **No camera is bound to the scan screen
  yet:** `:app` does not carry CameraX itself, so the screen says the camera is
  unavailable and takes the code from the keyboard, through the same validation.

### The cart flow

- **The resolution ladder**, rungs 0 to 3 plus the review queue at 5: user
  corrections, GTIN hits, the household alias cache, and a deterministic scorer
  over shop search results that emits a score, a band and plain-language
  reasons. Only a strong match goes straight into the cart; everything weaker
  goes to review with its top three candidates. A match nothing weighed carries
  no number at all. No fabricated percentages, anywhere.
- **Rung 0 is real, and it is fed from the review screen.** Swapping a guess for
  another candidate writes the correction to the alias cache there and then,
  rather than at Accept, because it is a statement about identity and stays true
  whether or not that cart is ever written. The cache is a Room table and the
  correction survives a restart, which it did not until 2026-09-15: until then
  `ServiceLocator` wired an in-memory cache and the "a human correction outranks
  everything" property lasted as long as the process did.
- **The model seam, and what goes in it.** Rung 4 disambiguation is reached only
  when rung 3 lands below the strong band, only over candidates rung 3 already
  fetched, and only through an injected client, so every ladder test runs
  against a scripted one. `ServiceLocator` builds a client from the provider
  settings a person saves, so rung 4 fires on a phone where somebody configured
  one and nowhere else. With no provider configured, rungs 0 to 3 fill a cart
  and the screen that wanted rung 4 says why it could not have it.
- **Rung 4 may choose without being allowed to remember.** A model pick at
  MODERATE normally writes an alias, and two conditions withhold it: when the
  quality lines in its prompt could have chosen for it, and when two candidates
  on the shortlist carry the same name. The second came out of a real catalogue:
  four products called "Leche entera Hacendado" differ only in fields that are
  not the title, tie one point under the strong floor, and are handed to a model
  that has been shown no reason to prefer any of them. The line still stands for
  that proposal; only its permanence is withheld. The question itself is still
  asked badly, which is `GAP-FORMAT-1`.
- **The Accept gate**, reachable from the list. A run snapshots the unchecked
  items at kickoff and holds no handle to live list state. It assembles a
  proposal of matched lines, guesses and misses with a running total, re-matches
  against the current list at Accept, and writes nothing at all on Discard.
- **The spend ceiling**, checked in the shop adapter before a single write, and
  returning a refusal rather than a truncated cart.
- **The candidate clamp.** A matcher answer outside the candidate set the shop
  returned is a rejected response, not a product: the index is bounds-checked in
  the client and again in the ladder, and the product it names has to be one the
  scorer weighed. That structural bound, rather than any scrubbing of product
  titles on their way into a prompt, is what keeps third-party text from acting
  as an instruction.
- **The failure table**, implemented rather than aspirational: no network, no
  key, an expired shop session, a shop that changed shape mid-run, a ceiling
  breach, an ambiguous match, a list edited during a run. Each one has a defined
  state and a sentence that says what happened and what to do. The session case
  is driven by what the adapter reports about itself, which is why it works
  before any adapter has a login to lose.
- **The settings screen**, reachable from the list: the spend ceiling, the daily
  model-call ceiling and the calls spent against it, the provider fields, the
  terms acknowledgement, the installed version, and the privacy block that
  states each claim's edge beside the claim. Every edit writes through as it is
  made. The key field and the connection test are drawn but not yet connected to
  anything: no keystore behind the one, no client behind the other, so a typed
  key does not outlive the screen and the probe always reports the endpoint
  unreachable.

### Fixture-backed, and only fixture-backed

- **`FakeShopAdapter` is the only `ShopAdapter` implementation.** A few hundred
  canned Spanish products, deterministic search, an in-memory cart, and a
  probe that reports capabilities honestly. It is what makes the cart flow
  testable on the JVM in milliseconds, and it is what lets the app be
  demonstrated without touching a retailer.
- **No real supermarket adapter ships.** There is no Mercadona adapter, no
  Carrefour, none at all. You cannot fill a real cart with this build. Swapping
  one implementation in behind the interface the fixture already implements is
  the next step, with the etiquette rules and the spend ceiling tested around it
  rather than inside it, and it is deliberately not the foundation everything
  else sits on.

### Not built yet

- **Sync, households and sharing.** The domain types exist; the relay, the merge
  rules, the invite-link capabilities and the multi-device story do not. Today
  the app is one phone. Sync is last in the build order on purpose: it is the
  hardest thing to change once real data is in it.
- **A supermarket login.** No web view, no `CookieManager`, no session storage
  anywhere in the tree. The session-custody rules are written down because the
  design turns on them, and there is nothing to keep custody of yet.
- **Bring-your-own-model, end to end.** The ladder's seam and the settings
  fields exist; the keystore binding behind the key field, a client the ladder
  can call, the live provider probe and a readable local audit log do not. The
  resolution log is written on every resolve and is not shown anywhere.
- **Recipe extraction** from shared text. The share sheet takes the text and the
  rule parser does what it can with it; pulling ingredients out of prose is the
  model parser's job.
- **The opt-in anonymous match corpus**, its k-anonymity threshold, and the
  tenancy audit test that will keep it anonymous.
- **Wake-up notifications.** The decision is made (polling by default, a
  foreground service only for the duration of a cart run, UnifiedPush opt-in),
  the implementation is not.
- **`:data:sync` as a module.** Absent rather than empty, so nothing imports a
  stub.

### Build and test

- Every Gradle invocation runs inside the project's Docker image through
  `scripts/dgradle`, so a host JDK and Android SDK are not required and CI and a
  laptop compile the same bytes. This also makes F-Droid's reproducible-build
  requirements tractable later.
- `:core:model`, `:core:parse`, `:agent` and `:shop` are plain JVM modules with
  no Android or Compose imports, so the whole cart flow, the part where a
  mistake costs money, is tested with fakes and no emulator.
- The APK does not embed AGP dependency metadata and does not embed git VCS
  info, both of which F-Droid rejects or reproducibility trips over.
- Three GitHub workflows are in place: the suite and the debug APK on every push
  and pull request, CodeQL for Java and Kotlin, and the release job that builds
  and signs an APK from an existing tag. There is no secret scan yet, which is
  the one piece of the CI story still missing.

### License and distribution

- Apache-2.0 from the first commit, with a `CLA.md` in place before the first
  external contribution, which preserves the option to license a future relay
  under AGPL-3.0-or-later without re-collecting permission.
- No telemetry, no analytics, no crash reporting, no Play Services, no Firebase,
  no third-party SDKs. This is a constraint of the design, not a default that
  can be flipped.
- F-Droid plus a direct APK is the intended distribution. Play is an open
  question to answer with a shipped app, not a plan.
