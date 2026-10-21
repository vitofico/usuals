# Architecture

The device keeps every list it shows in Room, and the UI reads Room without
waiting for a network. Signed in, the account's lists are also held by the
server, which is the authority for them.
`ServiceLocator` is the composition root and constructs production components;
UI ports keep storage and wire implementations out of screens.

## Modules

| Module | Responsibility |
|---|---|
| `:core:model` | Item ids, quantity, validated GTIN, version constants |
| `:core:parse` | Local utterance parsing and normalization, automatically discovered input-language packs |
| `:data:local` | Room lists, items, trips, purchases, preferences, aliases, pending changes and isolated product-quality cache |
| `:sync` | Shared list reducer and wire types, compiled into both the app and the server |
| `:smart` | Categories, autocomplete, explicit repeats, staples, gated intervals and replay |
| `:recipe` | On-device page reads, Recipe JSON-LD extraction and ingredient review planning |
| `:llm` | Optional recipe parsing, provider probe and persistent daily call budget |
| `:enrich` | Opt-in Open Food Facts scan naming, local cache and shared rate limiting |
| `:auth` | Keystore model key, provider settings and the account connection |
| `:capture` | Speech and ZXing barcode adapters |
| `:testing` | Shared test helpers |
| `:app` | Compose screens, Android entry points and dependency wiring |

`:core:model`, `:core:parse`, `:smart`, `:recipe`, `:llm` and `:enrich` are pure
Kotlin JVM. All display sentences belong to `:app` English resources; library modules return structured values,
including model problems and capture failures. New UI languages add complete
locale folders. AGP generates the system locale list from the resource files.

## Storage

Room schema 8 keeps named lists, their scoped shopping facts and, for lists that
belong to the signed-in account, the server's last projection and an outbox of
pending intents. A projection is stored in parts that each fit Android's 2 MB
cursor window. Each schema since the finalized schema 4 has a versioned
migration that preserves shopping facts. Destructive fallback remains limited to
unreleased schemas 1, 2 and 3. Every later schema change needs its own migration,
exported schema and migration tests.

The pure JVM `:sync` reducer uses accepted intent revisions rather than phone
clocks. `LocalReplica` commits its projection and durable source intent with
Room writes. Finish records final checked facts once; Undo retracts only its
close and unchanged automatic rows, including after process restart. A server
answer or feed page commits the confirmed projection and cursor together before
pending work is applied on top again. The UI reads items and history in one
transactional snapshot per selected list.

Archive version 2 transfers named lists and scoped shopping facts; version 1
still imports. Account lists become independent local copies. Archives exclude
keys, the account connection, sync state and product cache. The
[local foundation](sync/local-foundation.md) describes the implemented boundaries.

## Trust boundary

Recipe-page and Open Food Facts reads originate on the client. The optional
model can use an explicitly configured direct native provider or the chosen
server's authenticated AI gateway. The gateway receives only explicitly submitted
recipe text or prepared photos; list sync is separate.
Its provider revisions, credential encryption, image checks and resource isolation
are described in [ADR-0014](decisions/ADR-0014-server-connection-and-ai.md).
Cluster deployment requires separate approval.
See [security-model.md](security-model.md) and the [connection guide](server-connection.md).

## Day-one memory

Category seeds live beside English, Spanish, Italian, French and German input grammar in the language
packs. Manual category overrides, explicit repeat intervals and confirmed
aliases live in shopping preferences. History ranks autocomplete without
inferring a quantity. The editor saves name, quantity and preferences together.

Only explicit repeat rules add rows automatically. A due rule adds a marked
row with no guessed quantity. Removal snoozes that identity until a later shop
closes. Finish and repeat maintenance share a transaction and a lock; Undo
retracts unchanged automatic rows whose eligibility depended on that close,
while preserving subsequent edits. Confirmed barcode/name aliases keep repeated
scans and typed entries on the same identity.

## Suggestions and evaluation

The suggestion row contains at most six canonical identities: due explicit
rules, eligible intervals if enabled, then staples present in at least half of
the latest eight trips. Active items and muted or snoozed preferences suppress
candidates. Add writes a marked row; Not now lasts until a later trip closes;
Never persists a mute. No inferred item is added automatically.

Intervals require three distinct purchase trips, a median gap of at least five
days, bounded variation and no long dormancy. They remain disabled in the
production graph. The local walk-forward replay command is documented in
[development.md](development.md); synthetic checks do not authorize rollout.

## Quantities and confirmed names

A quantity hint requires two equal amounts among the last five purchases. The
hint is derived and grey until tapped. Its transaction rechecks that the row
still has no amount, so an explicit quantity always wins. No suggestion or
autocomplete silently copies a previous quantity.

After two logged suggested purchases and a third accepted row, Usuals offers
an explicit repeat interval based on those dates. Only confirmation saves a
rule. Dismissed offers are remembered locally to avoid repeated questions.

Unknown similar capture names wait for a choice before insertion. Confirmation
writes the alias and adds the explicitly requested amount in one transaction.
Keeping names separate adds the original request and remembers the declined
question locally. The database stores positive aliases; later captures honor
those decisions without another question. Normalization rules are unchanged.

## Recipe import

Shared URLs and the recipe button enter the same review sheet. RecipeFetcher
reads from the phone, with a 4 MiB and 30-second ceiling, no script execution,
no linked-context fetches and no retry loop. RecipeJsonLd reads Recipe objects
inside graphs and arrays, ingredient lists, yields and nested HowToSections.
IngredientLineParser keeps preparation commas within one ingredient, handles
fractions and preserves unconvertible source measures. Printed metric
equivalents may be used directly; conversions are not guessed.

Review merges identical canonical ingredient keys. Existing list items are
excluded; pantry staples and purchases from the last seven days start unticked.
Confirmation rechecks current list identities in a transaction. Recipe content
is temporary and is not stored as a recipe collection.

A page without structured ingredients offers an explicit model action. No
structured page calls a model. The source is a JSON string value, provider
output is reviewed, and the saved daily ceiling applies to both reads and
probes. Usage survives process restarts. ServiceLocator constructs the reader,
extractor, ingredient parser, review planner, recipe port and persistent budget.

## Shopping surfaces

Confirmed scan names update the GTIN alias and use Open Food Facts category
tags unless a manual override exists. The product name and category tags stay
in the local quality cache, outside shopping-data exports. Names are never
applied without confirmation. Each camera read pauses capture until Scan next
item is chosen, and the screen accepts each canonical barcode once per session,
including after rotation. Rows insert independently of queued reads; lookup
controls and name offers are available in the scanner. Missing names distinguish
opt-out, absent records, connection failures and store-specific codes. Confirmed
aliases supply names on later scans after Finish or removal, without a network
request.

Optional store tags are saved with rows and filter the list while shopping.
Finish still closes every checked row, regardless of the current filter. The
home-screen RemoteViews widget shows up to six unchecked rows plus a count.
A Room flow updates installed widgets while the process is alive, and Android
widget updates refresh from local storage. There is no polling or network job.
Tapping the widget opens the living list even if Settings was last visible.

## Server-held lists

The separate `server/` build uses Kotlin/Ktor, PostgreSQL and the same pure
`:sync`/`:core:model` sources with its own compiler, outputs and dependency locks.
It stores each list's projection encrypted at rest, applies intent batches through
the shared reducer under a lock on the list, and orders every change with one
server-wide counter that feeds each account's change feed. No Android/Room
dependencies or third-party shopping reads enter the server. See the
[server guide](../server/README.md) and the [wire contract](sync/lists-v2.md).

On the phone, `ServiceLocator` constructs `ListSync` and its scheduler. Signed in,
a local change is sent about two seconds later; while the app is open the phone
waits on the change feed, and WorkManager syncs every 15 minutes in the background.
Failures back off and keep the outbox. The share sheet and account screen read the
same Room records. The browser keeps no copy: it reads the feed and renders what
the server returns.

Personal shopping tools live in a bounded, versioned app preference store, wired
by ServiceLocator. Mode, haptics and deferral never become list facts on the server. Deferred
rows stay unchecked; a new completed trip releases their local hiding. Favourites
and per-store category ordering travel in app archive version 3. Room facts still
use the existing transactional version-2 importer. Preference storage follows
that transaction and is idempotent on retry; a preference disk-write failure may
leave facts imported before the favourites/layouts are restored. No account or
sharing state is imported.
