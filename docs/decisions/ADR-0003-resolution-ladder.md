# ADR-0003: Deterministic before model, as a ladder with a cost per rung

- **Status:** Superseded (2026-10-15) by [ADR-0012](ADR-0012-smart-shopping-list.md)
- **Deciders:** Comanda founding work
- **Relates to:** design v0.2 §6 (the resolution ladder), §9.2 (cost and
  budget). Imported from quire's Tier-1 / Tier-2 affinity split.

## Context

"The LLM is a fallback" is an intention, and intentions do not survive contact
with a shopping list of forty items. quire made the same claim about its affinity
scoring and only made it true by splitting the work into tiers with a stated cost
each, then measuring how often it reached the expensive tier.

Matching "leche entera" to a product is mostly not a language problem. It is a
cache hit, a barcode, or an arithmetic comparison of token overlap, pack size and
unit price. The model is needed for a narrow band: several plausible candidates
the scorer cannot rank. Sending every item to a model because a few need one is
how a shop costs euros instead of cents and stops working when the key is gone.

## Decision

Resolution walks a ladder, and each rung has a known cost:

| Rung | Mechanism | Cost |
|---|---|---|
| `USER_CORRECTION` | a human previously fixed this exact item for this shop | zero |
| `GTIN` | the item was scanned, or carries a GTIN from a prior resolve | zero |
| `ALIAS` | exact normalized alias: household cache, then the opt-in corpus | zero |
| `DETERMINISTIC` | scorer over shop search results | one search request |
| `MODEL` | disambiguation over the candidates rung 3 already fetched | one small call |
| `HUMAN` | present the top three with reasons | free, but spends attention |

1. `Rung` lives in `:core:model` and its **ordinal order is ladder order**.
   Every `Match` records the rung that produced it.
2. `MODEL` is reached only when `DETERMINISTIC` lands in an ambiguous band, and
   only over the candidate list `DETERMINISTIC` already fetched. It never issues
   its own search.
3. **Never fabricate a score.** `Match.score` is nullable and MUST be null when
   the band is `UNKNOWN`, enforced in the type rather than by convention. quire
   refuses to score a book from a three-book library; this refuses to guess between
   three olive oils, which the fixture shop contains on purpose. An ambiguous item
   goes to the review queue, not to the cart.
4. **Version the rubric.** Every cached alias carries a `match_version`, bumped
   whenever weights or normalization change. Anything the model produced carries
   a `prompt_version`. A rubric change then invalidates cleanly instead of
   silently mixing generations.
5. **Instrument it.** The resolution log records one row per resolve: rung,
   band, latency, status, and whether the item spent a model call. It is device-local, never uploaded, and it lives in its own
   table, separate from the cache, so per-call accounting can never contaminate
   a cache key (quire keeps `ai_generation_log` apart from its cache tables for
   exactly this reason).

## Consequences

- ✅ The cost claim becomes a measurement. After two weeks of real shopping the
  rung histogram says whether the cache thesis holds. If `MODEL` still fires on
  40% of items, the deterministic scorer is what needs work, not the prompt.
- ✅ The product works with no model configured: rungs 0 to 3 fill a cart, and
  only rung 4 is greyed out with a one-line why.
- ✅ The log doubles as the debugging surface when a match goes wrong, and it is
  never telemetry, because it never leaves the phone.
- ⚠️ The deterministic scorer is real work. As built it reads four signal
  families, name overlap, unit agreement, pack-size sanity and brand, each emitting
  a reason string the proposal screen can show. A price-plausibility family was
  considered and is not implemented: it is the softest signal and the one that goes
  wrong on legitimately premium goods, so it can be added later, with a range and a
  sentence like the others, if the instrumentation asks for it.
- ⚠️ The confidence floor cannot be chosen from a document. It is an open question
  answered by instrumented shopping in step 4 of the build order.
- ⚠️ A nullable score forces every UI surface to handle "not sure about this one"
  rather than printing a badge. That is the point, not a cost.

## Alternatives considered

- **Model first, deterministic as a sanity check.** Rejected: cost and latency
  scale with list length, the cache stops paying for itself, and the app becomes
  unusable without a key.
- **One confidence number, no bands.** Rejected. A percentage implies a
  calibration we do not have. Bands plus reasons say what we actually know.
- **A fixed threshold with no rubric version.** Rejected: the first weight change
  silently mixes two generations of cached matches, and the symptom surfaces
  weeks later as an unexplainable regression.
