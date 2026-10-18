# ADR-0011: Opt-in barcode product details directly from the device

- **Status:** Accepted (2026-09-15), rewritten 2026-10-15 under [ADR-0012](ADR-0012-smart-shopping-list.md)
- **Relates to:** ADR-0004, `OpenFoodFactsEnricher`, `RepositoryScanStore`

## Context

An empty package gives the list a barcode, but a person still needs a readable
name. Open Food Facts can offer that name and taxonomy category. It is an
optional third-party read with crowd-sourced coverage, not a source of inferred
shopping items or a product matcher. The earlier quality-ranking design is
retired and remains in Git history.

## Decision

1. Scan lookup is off by default. With it off, scanning makes no request and
   offers manual naming. Settings' privacy section identifies the barcode as
   data that leaves the phone when lookup is enabled. No list or history is sent.
2. Every third-party read originates on the device. No Comanda server or relay
   participates in fetching Open Food Facts records, recipes or model responses.
   This boundary applies independently of any retired retailer ADR and also
   constrains a future encrypted sync relay.
3. Insert the barcode row before lookup. Queue reads behind the shared limiter
   rather than skipping rapid scans. Single flight shares overlapping identical
   reads; restricted-circulation barcodes are not sent. Recheck opt-in after a
   queued permit, so revocation stops a waiting request from being issued.
4. Read the product endpoint with a meaningful User-Agent and explicit fields,
   including localized product and generic names, category tags, brand, pack size,
   ingredients, nutrition grades, allergens, traces and source modification time. No typed-name search, bulk
   crawl, proxy, redirect-following or automatic retry is used. Bound timeouts
   and respect the limiter's backoff when the service asks it to slow down.
5. Offer the product name for confirmation. On confirmation, map taxonomy tags
   to a category unless the person has overridden it, and save the GTIN alias.
   Manual edits and late results cannot silently replace a name. A failed or
   missing lookup falls back to manual naming.
6. Show Open Food Facts attribution and ODbL credit beside offered data, with a
   link to its source. Keep the product cache local and outside shopping-data
   JSON exports and any future sync payload. Direct database device transfer
   may carry the local database, including its cache, to the new phone.
7. Cache confirmed answers and misses, never network failures. Refresh old data
   on demand; if refresh fails, a known cached name may still be offered.
   Missing nutrition/allergen metadata means unknown and never means safe.

## Consequences

Network availability does not gate capture. Product names remain a person's
choice, ten quick scans can add ten rows while reads wait, and turning lookup
off disconnects that optional data path. Barcoded rows expose a product-details
sheet with cached data immediately, its saved date, explicit refresh, source
link and contribution entry point. Failed refreshes preserve saved details;
missing and empty allergen/trace arrays stay distinct, and packaging remains
the source to check. The legacy environmental grade remains cache-compatible
but is not displayed: its current Green-Score needs country-specific context
that Aurea does not collect. Brand and pack size also help review a proposed scan name.
Names fall back from the UI language to the source name and English, then generic
names. A brand alone is never treated as a name. Pack size never sets shopping
quantity. No score is assigned to a typed shopping name.

Responses are bounded to 1 MiB and returned barcodes must match the requested
canonical GTIN when present. Only a `product_not_found` result is a confirmed
miss. Room migration 5 to 6 adds nullable product-cache columns without changing
shopping rows, aliases, JSON archives or relay operations. Cached details can be
read with lookup off. Opening an old cache entry refreshes it only with consent;
refresh shares the scanner client, in-flight requests and 15 reads/minute budget.
The product page link opens only on an explicit tap; contributions happen on
Open Food Facts, with no product writes or OFF credentials handled by Aurea.

API reference checked 2026-10-18:
[Open Food Facts API documentation](https://openfoodfacts.github.io/documentation/docs/Product-Opener/api/).
