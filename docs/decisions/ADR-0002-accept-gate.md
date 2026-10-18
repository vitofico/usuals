# ADR-0002: The agent proposes, the human accepts

- **Status:** Superseded (2026-10-15) by [ADR-0012](ADR-0012-smart-shopping-list.md)
- **Deciders:** Comanda founding work
- **Relates to:** design v0.2 §5 (fill the cart), §11 (motion), invariants 1, 2
  and 3. Imported from galley's Accept gate and scratch isolation.

## Context

Galley's single best idea is that agent output never lands directly in the
document: the agent writes into a scratch buffer, the human sees a diff, and an
explicit Accept is what moves it. That maps onto a shopping cart almost without
translation, and here the stakes are plainer than a document edit, because the
thing on the other side of the gate has money attached to it.

There is a second problem a grocery list has that a single-author document does
not. The list is shared. A fill run takes forty seconds to a couple of minutes,
and during that window a housemate can add olives, check off the milk, or delete
the bread they just bought on the way home. A run holding a live handle to the
list is a run whose input changes underneath it, which makes it both incorrect
and untestable.

## Decision

1. **The Accept gate is mandatory** (invariant 1). No agent action reaches the
   live cart without an explicit human Accept. The agent produces a proposal;
   the human disposes. Discard writes nothing, and "nothing" means the shop
   never saw a request.
2. **Scratch isolation** (invariant 2). A run operates on an immutable snapshot
   of the unchecked items, taken at kickoff. It holds no handle to live list
   state. A housemate adding olives mid-run cannot corrupt the run, and cannot
   sneak an item into a proposal the user is already reading.
3. **Conflict-aware Accept** (invariant 3). On Accept, re-match the proposal
   against the *current* list. Items checked off or deleted during the run are
   surfaced in the summary, never silently carted. Items added during the run
   are not in this proposal and the screen says so.
4. **The animation belongs on the review screen.** The one orchestrated moment
   in the app, each list item resolving into its matched product in sequence,
   plays on the proposal screen where catching a wrong guess is still free.
   Animating a write you cannot undo is theatre; animating a review is useful.
5. The proposal screen groups matched (collapsed, counted), guesses (expanded,
   with the rung-3 reason string, tap to swap) and not found (expanded, drop or
   keep for the physical shop). The running total sits against the ceiling, and
   the Accept button carries the total in its label.

## Consequences

- ✅ Nothing irreversible happens without a human looking at it, which is the
  only defensible posture for an agent that spends money.
- ✅ A run becomes a pure function of (snapshot, adapter, model). It is
  replayable, JVM-testable, and its failures are reproducible from a fixture.
- ⚠️ The flow costs a tap and a screen. The proposal screen earns that back by
  being where mistakes get caught.
- ⚠️ The re-match at Accept is genuine extra code with its own tests. The
  alternative is silently buying something a housemate already bought.
- ⚠️ A proposal left open gets stale: prices move, stock moves. The screen states
  the snapshot time, and the spend ceiling is re-checked on the write path
  against the prices the write actually uses (ADR-0008).

## Alternatives considered

- **Auto-accept when every match is STRONG.** Rejected. The band is our own
  estimate of our own scorer, so auto-accepting on it means trusting a guess
  about a guess with the user's money. Galley only allowed opt-in auto-accept
  after it had authenticated proposal provenance, a much later problem.
- **Live list handle instead of a snapshot.** Rejected: the run stops being
  deterministic, so it stops being testable, and mid-run edits become a race
  rather than a diff.
- **Accept without the conflict re-match.** Rejected: it is the cheapest
  available way to buy a second loaf of bread.
