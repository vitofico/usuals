# ADR-0004: Validated GTIN identity and confirmed shopping aliases

- **Status:** Accepted (2026-09-10), amended 2026-10-15 under [ADR-0012](ADR-0012-smart-shopping-list.md)
- **Relates to:** ADR-0011, `Gtin`, `Normalizer`, `item_alias`

## Context

A package barcode identifies a packaged product. A household's shopping name
such as milk is a separate, deliberately broader identity. The pivot removes
retailer products and keeps those two identities connected only by a person's
confirmed rename. The original retailer/corpus rationale remains in Git history.

## Decision

1. Construct GTIN values only through `Gtin.parse`. Accept EAN-13, UPC-A and
   EAN-8 with a valid check digit and store one canonical EAN-13 form. Invalid
   input never becomes a barcode row.
2. A scan immediately adds a local row carrying its GTIN, without waiting for a
   name lookup. Its initial display is the barcode. Scanning needs no model.
3. A shopping identity is a normalized key. Preserve the source name and
   normalizer version in purchases so future normalization changes can rebuild
   those keys. Bump `NORMALIZER_VERSION` when identity rules change.
4. A confirmed scan rename writes `gtin:<canonical barcode>` to the chosen named
   key. A later rename updates that association. Repeated scans and typed names
   honor confirmed aliases rather than silently inventing a merge.
5. Similar names ask before merging. A positive choice stores an alias; keeping
   names separate does not create one. Validate imports against alias cycles.
6. Optional Open Food Facts names/categories are proposals. They do not rename
   an item until confirmation, and they do not overwrite a manual category.

## Consequences

Shopping names remain useful across brands and stores. A barcode keeps its
validated identity even when a person edits the visible name. No retailer id,
shared corpus or model-generated product identity is part of this contract.
