# ADR-0012: A smart shopping list with local purchase history

- **Status:** Accepted (2026-10-15)
- **Supersedes:** ADR-0001, ADR-0002, ADR-0003, ADR-0006, ADR-0007, ADR-0008
- **Amends:** ADR-0004, ADR-0005, ADR-0011

## Context

Retailer automation introduced account and endpoint risks while the local list
had no purchase memory. The settled pivot removes that entire integration and
makes local history the useful difference from a note.

## Decision

1. Remove retailer modules, product matching, proposals, spending controls and
   retailer terms. The old invariants governing those features are historical.
2. Keep capture offline. Store purchases at trip close from each row's final
   state, with item-derived purchase ids and the original check timestamp.
3. Only explicit repeat rules add automatically. Inferred habits offer reviewable
   suggestions. Interval predictions remain off until real-history replay beats
   last-trip and top-six-staple baselines.
4. English is the default UI. All display sentences belong to `:app` resources;
   adding a translation means adding complete locale files. Input packs are
   independent and preserve Spanish and English input.
5. A model is optional, off by default, and reserved for unstructured recipes.
   Recipe input is untrusted JSON string data and output is reviewed.
6. No server participates in any third-party read. Provider, recipe and opt-in
   barcode requests originate on the device. Scan lookup sends nothing while off.
7. Keep quality data isolated and local. User-data exports exclude it and all
   secrets. Permit database transfer without cloud backup or secret transfer.
8. Schema 4 replaces unreleased schemas 1 to 3 once. All subsequent schema
   changes require migrations and migration tests.
9. Work locally through the ordered pivot phases. Sync is separate and needs
   explicit approval before starting cluster-dependent implementation.

## Consequences

The new pure JVM modules are `:smart` and `:recipe`. `:llm` retains provider
probing, parsing and the daily call budget. `ServiceLocator` remains the sole
composition root. ADR-0009 and ADR-0010 remain accepted. Historical ADR text is
preserved with explicit supersession status so it cannot be mistaken for the
current product contract.
