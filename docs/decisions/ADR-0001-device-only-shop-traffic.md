# ADR-0001: All shop traffic originates on the device

- **Status:** Superseded (2026-10-15) by [ADR-0012](ADR-0012-smart-shopping-list.md)
- **Deciders:** Usuals founding work
- **Relates to:** design v0.2 §4 (trust boundary), §8 (shop abstraction),
  invariant 4, RISK-TOS-1

## Context

Usuals fills a real supermarket cart using the user's own shop account. There
are two places that work can happen: on the phone, or on a server holding the
user's session. The server version is tempting: it makes scheduled fills,
background retries and a future web client easy.

The privacy argument against it is the obvious one. A supermarket session cookie
is functionally the account: possession is checkout. Holding a few thousand of
those on a machine we operate creates a custody problem that nothing in this
product needs to take on.

The load-bearing argument is different, and it is about traffic shape. From
Mercadona's side, a phone doing a fill run is one residential mobile IP, one
genuine browser session, searches arriving at human speed, one cart write. That
is indistinguishable from a customer, because it is a customer. A relay filling
carts for five hundred households is five hundred sessions from one datacentre
IP range on one autonomous system, correlated in time, with no browser behind
them. That is a scraper, and it is detected as one.

The consequence of being detected does not land on us. It lands on the users,
whose shopping accounts get suspended (RISK-TOS-1). Their account, their weekly
shop, our architectural choice.

## Decision

**No server-side shop access, ever, for any feature** (invariant 4). If a
feature appears to require it, the feature is wrong.

1. Every `ShopAdapter` implementation lives in `:shop` and runs on the device.
   Search, cart writes and checkout URLs all originate from the phone, over the
   user's connection, with the user's session.
2. Shop session cookies live in the WebView `CookieManager` inside the app
   sandbox. They are never uploaded, never logged, never included in an error
   report (invariant 5).
3. The relay follows quire's push model, generalized: it knows only what a
   client pushed to it, and it makes no outbound call into the user's
   environment.
4. Etiquette is part of the `ShopAdapter` contract, not a nicety: a descriptive
   User-Agent, roughly 10 second timeouts, sequential human-paced searches
   rather than a parallel fan-out, a single attempt plus user-driven manual
   retry with no automatic backoff storm, and a circuit breaker that stops the
   run and says so after N consecutive failures.

**As implemented.** Point 1 holds and points 3 and 4 hold in part. Etiquette turned
out to belong in a decorator rather than in each adapter: `RateLimitedShopAdapter`
wraps any `ShopAdapter` and owns the serialization, the 700 ms pacing and the
breaker at three consecutive failures, which is the same rule enforced in one
place instead of promised in several. The User-Agent and the timeouts are still
owed, because nothing in `:shop` opens a socket yet. Point 2 is entirely ahead of
the code: there is no login, no WebView and no cookie store anywhere in the tree,
and the feasibility spike that would produce one has not been done
([GAP-SHOP-1](../known-issues.md#gap-shop-1--there-is-no-real-shop-adapter--high)).
This ADR is what that work will be held to, not a description of it.

## Consequences

- ✅ Traffic stays customer-shaped. The blast radius of a block is one account,
  not every account.
- ✅ No session material on a server we run: nothing to leak, nothing to subpoena.
- ⚠️ A fill run only happens while the phone is on and connected. Hence the
  foreground service for the duration of a run (ADR-0009), and hence no
  "fill my cart overnight".
- ⚠️ Cross-chain price comparison, server-side cart pre-warming and a web client
  that shops for you are all off the table. Named here so the argument is not
  re-had.
- ⚠️ Every adapter fix ships as an app update, which is why the circuit breaker
  carries an adapter version string in its error text (RISK-BREAK-1).

## Alternatives considered

- **A server-side cart service.** Rejected on both arguments above: it takes
  custody of session material, and it converts every household's shop into
  datacentre traffic that looks like scraping.
- **Hybrid: server does search, device writes the cart.** Rejected. Search is
  the overwhelming majority of requests, so this moves most of the fingerprint
  to the datacentre while keeping all of the device-side complexity.
- **A relay the user runs on their own network.** Rejected as a requirement: for
  a household of three it is the device model with a box to maintain. Nothing
  forbids someone doing it later behind the same adapter.
