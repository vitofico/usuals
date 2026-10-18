# ADR-0006: A fixture-backed shop adapter ships permanently

- **Status:** Superseded (2026-10-15) by [ADR-0012](ADR-0012-smart-shopping-list.md)
- **Deciders:** Comanda founding work
- **Relates to:** design v0.2 §8 (shop abstraction), §10 (testing), §16 (build
  order). Imported from galley's offline Demo agent.

## Context

Galley ships an offline Demo agent so that the product does something useful
before the user has made a single decision about providers or keys. Comanda needs
the same thing for three separate reasons, and any one of them would be enough.

**The product.** Capture, editing, scanning and sharing all work with nothing
configured. If the cart flow does not, the app's second-most-interesting screen
is a login wall, and a new user cannot see what they are signing up for.

**CI.** No continuous integration job is going to log in to a supermarket. Without
a fake shop, the cart flow, the proposal screen, the Accept gate and the spend
ceiling have exactly zero automated coverage, forever.

**Build order.** Steps 1 through 3 need a shop before step 4 has one, and the
Mercadona adapter is the riskiest component in the project (RISK-BREAK-1). It
should be a swap behind an interface that already has a passing test suite,
rather than the foundation everything else sits on.

## Decision

**`FakeShopAdapter` ships in `:shop` from the first commit, in release builds,
and is never removed.**

1. Fixture-backed: canned Spanish grocery products with realistic titles, brands,
   pack sizes, prices and unit prices, roughly eighty of them today, growing when a
   scorer case needs one rather than to reach a number. Every one carries a
   syntactically valid EAN-13 built from its serial, so the fixture exercises
   exactly the check digit and the same `Gtin` path a scanned code does.

   > **Corrected 2026-09-15.** This point used to say the codes were "not a real
   > barcode: no lookup will ever resolve one". That was false, and it was found
   > by running the app. The serials carried an `84` prefix, which is Spain's,
   > and five of the nine barcodes a fill produced resolved in Open Food Facts to
   > real, unrelated products: `8400000000017` is a bag of breadsticks and
   > `8400000000031` is a snack mix by a company called agromart. Open Food Facts
   > is crowd-sourced, so "I made this number up" was never the same statement as
   > "this number identifies nothing". The fixture serials now live in the GS1
   > restricted-circulation band (02, 04, 20 to 29), which is what they always
   > were: numbers meaningful only inside a shop that does not exist. That also
   > makes it self-enforcing, because `RestrictedCirculation` declines to look up
   > anything in that band. The decision this ADR records is unchanged.
2. **Deterministic.** The same search terms return the same products in the same
   order. No clock, no randomness, no reliance on hash map iteration order. A
   scorer test that passes today passes in a year.
3. It implements the whole contract honestly: `probe()` reports what it can do
   like any other adapter, and `setCart` enforces the spend ceiling like any
   other adapter, so the refusal path is covered by tests (ADR-0008).
4. Every ladder test in `:agent` runs against `FakeShopAdapter` and a scripted
   `LlmClient`, on the JVM, in milliseconds, with no network. `MercadonaAdapter`
   gets its own thin contract test against recorded fixtures, which is a
   different job.
5. The fixtures deliberately include the hard cases: three olive oils that differ
   only by size, milks that differ only by fat content or by brand, a pack size
   nobody would buy, and an out-of-stock product. The hostile title from ADR-0007
   is not one of them, and deliberately so: it belongs to the prompt path rather
   than to the catalogue, and it is exercised where that path is tested, in
   `:agent`'s client tests.

## Consequences

- ✅ The cart flow has real CI coverage from step 3 onward, before a single line
  of retailer-specific code exists.
- ✅ The app can be demonstrated to anyone without touching a retailer's servers
  or risking anybody's account.
- ✅ Swapping in the real adapter is a change behind a green suite. When it goes
  red, the suite says which part of the contract broke.
- ⚠️ The fixture corpus is maintenance. It has to stay plausible enough that
  scorer tests mean something, which means writing Spanish product titles with
  real pack sizes rather than "Product A".
- ⚠️ A fake that is too kind hides scorer weaknesses. Ambiguity has to be authored
  in on purpose, which is why point 5 is part of the decision rather than a
  testing detail.
- ⚠️ It ships in the release APK and costs some kilobytes. Cheap.

## Alternatives considered

- **A test-only fake under `src/test`.** Rejected: then the shipped product does
  not work with nothing configured, and the demo path needs a second
  implementation that will drift from the first.
- **Record and replay real traffic as the only fake.** Rejected: that is the
  contract test for the real adapter, which is a different job with a different
  failure meaning. It also cannot cover ambiguous cases, because it can only
  contain what the shop happened to return.
- **No fake, develop against the live shop.** Rejected: no CI, no offline work,
  no deterministic tests, and the build order inverts so that the riskiest and
  least stable component becomes everything else's foundation.
