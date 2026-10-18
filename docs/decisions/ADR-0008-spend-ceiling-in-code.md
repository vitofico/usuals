# ADR-0008: The spend ceiling is enforced in the adapter, never in the prompt

- **Status:** Superseded (2026-10-15) by [ADR-0012](ADR-0012-smart-shopping-list.md)
- **Deciders:** Comanda founding work
- **Relates to:** design v0.2 §8 (shop abstraction), §9.2 (budget), §12 (failure
  table), invariant 6

## Context

A fill run assembles cart lines with real prices and writes them to a real cart
belonging to a real person. The user sets a ceiling because they want a bound on
what a mostly-automatic process spends on their behalf.

There are two places that bound can live: a sentence in the prompt ("do not
exceed 80 euros"), or a comparison in the code path that performs the write. The
first is a request, and it is the wrong shape for the problem, because most of
the ways a run overspends have nothing to do with the model. Rung 3 picks the
24-pack instead of the single, a pack size is misread, a unit price is per kilo
where the scorer assumed per unit. A model told about the budget prevents none of
those, and being told invites every later reader to believe that it does.

## Decision

**The ceiling is checked in `ShopAdapter.setCart`, against the assembled lines,
before a single write** (invariant 6).

1. `setCart(lines, maxSpend)` computes the total from the lines it is about to
   write and compares it to `maxSpend` before issuing any request to the shop.
2. Over the ceiling, it **returns a refusal, not a truncated cart**. A partial
   write is worse than no write: the cart is left in a state nobody chose, and
   the user cannot tell what is missing. The refusal names what pushed the total
   over, and the UI offers to drop the largest line (§12).
3. **The ceiling is never in the prompt.** The model is not told the budget at
   all, because telling it does not constrain it, and because the mere presence
   of the sentence would make the real check look optional.
4. Money is `Money(cents)`, integer minor units, everywhere. A ceiling comparison
   that goes through a `Double` is not a ceiling.
5. **Every adapter enforces it**, including `FakeShopAdapter`, so the refusal
   path is exercised in CI on every run of the suite (ADR-0006).
6. The daily model budget has the same shape: a hard count checked in code before
   a call, where exhaustion degrades the run to rungs 0 to 3 with a banner rather
   than failing it. quire ships this as `ai_daily_budget`, a number the user can
   see and change.

## Consequences

- ✅ The ceiling holds regardless of what the model, the scorer or the shop did.
  It is the one guarantee here that does not depend on anything being clever.
- ✅ Refusal is deterministic and testable, and the fake makes it a fast JVM test
  rather than an integration exercise.
- ⚠️ A refusal at write time feels late. Mitigated on the proposal screen, where
  the running total sits against the ceiling and the Accept button carries the
  total in its label, so Accept is never the first time the user learns the
  number.
- ⚠️ Prices move between proposal and write, so the check runs on the write path
  with the prices the write is actually using. The refusal can therefore appear
  after Accept, and the UI handles it as a normal outcome, not an error.
- ⚠️ Every future adapter has to implement it. That is stated in the interface
  KDoc and covered by the adapter contract test, which is the only kind of
  enforcement that survives a new contributor.

## Alternatives considered

- **Enforce in the orchestrator before calling `setCart`.** Rejected as the
  *only* place: any future caller that reaches the adapter another way skips the
  ceiling. Checking there as well is fine, but the adapter is authoritative,
  because it is the last code before the write.
- **Truncate the cart to fit the ceiling.** Rejected: silently dropping lines
  produces a shop where the milk is missing and nothing said so. That is a worse
  Tuesday than a refusal.
- **Tell the model the budget and check nothing.** Rejected. Guardrails are code.
