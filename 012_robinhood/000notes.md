# Design Robinhood (Stock Trading)

Source: https://systemdesignschool.io/problems/robinhood/solution

> Note on fidelity: like the rate-limiter reference page, this one is built almost entirely from JS-interactive widgets (a saga step-through with four scenario tabs, a seat-hold race widget, a grading accordion under each deep dive, collapsible API request/response panels, and a quiz with click-to-reveal answers) rather than static images. Everything behind those interactions — all quiz answers, every expanded request/response body, and the labels/flow of each diagram — was expanded live (via "Reveal All" and by opening each "Request & response" panel) and is transcribed below as text, in the same order it appears on the page. There are no downloadable diagram image files on this site (diagrams are live JS/SVG widgets, not `<img>` files) — so the 10 box/arrow diagrams below have instead been recreated as standalone SVG images (`images/d01`–`d10`), redrawn from screenshots of the live page to match its box shapes, colors, and layout, and are embedded inline next to each diagram's original text/code-block transcription. The interactive step-through widgets themselves (the saga scenario tabs, the seat-hold race, the estimation calculator) remain text-only, as on the ticketmaster reference page.

Tags: Hard difficulty · Real-time fan-out · Ledger consistency · Sagas

---

## Problem statement

Design a retail stock-trading app: users watch live prices for the symbols they follow, place buy and sell orders, and see their cash and share balances change as those orders execute.

In scope: streaming live quotes, placing and tracking market and limit orders, and maintaining cash balances and share positions as orders fill. Out of scope: the exchange's own matching-engine internals, options/crypto/margin trading, the trading UI itself, market-data provider integration specifics, and clearing/settlement (the process that finalizes money and share transfers the following business day).

## Clarifying questions

- **What has to be real-time — prices, or execution?** Prices stream continuously to everyone; order placement is a request, and execution happens asynchronously later at an exchange. Assume both, built as genuinely separate paths.
- **Do we run the matching engine?** No — orders route to an external execution venue that matches them. This system owns placement, routing, fills, and balances; the matching engine's internals are out of scope.
- **What's the correctness bar on money and shares?** Exactly-once effect: never double-execute an order, never oversell cash or shares a user doesn't have, and every fill must be reconcilable against the exchange's execution reports. This is the dominating requirement.
- **What order types?** Market orders (execute promptly at prevailing prices, subject to liquidity and partial fills) and limit orders (execute only at a specified price or better). Options, crypto, and margin are separate products to name and defer.
- **What consistency do balances need?** Strong — a balance read must reflect every committed fill, and a user can never spend cash or sell shares they no longer actually hold.

## What makes this problem distinctive

This problem is really two systems with opposite requirements, forced to work together. Market-data fan-out streams live prices to millions of read-heavy clients simultaneously — a broadcast problem where a stale value is instantly superseded and mostly harmless. Order and balance handling must move cash and shares correctly the moment an order executes: no double-execution on a retry, no overselling cash or shares under concurrent orders, and a complete audit trail. This is a strongly-consistent, ledger-grade problem, where the smallest inconsistency is someone's real money.

The two meet at exactly one point: the moment a user acts on a price they saw and that action becomes a trade. A decision made on a best-effort streamed price must still produce an exactly-once, strongly-consistent change to real cash and shares.

```text
Millions watching live prices at once ──forces──▶ A broadcast that tolerates dropped ticks ──resolved by──▶ A conflated price fan-out subsystem

An order must move real cash and shares ──forces──▶ Exactly-once, strongly-consistent writes ──resolved by──▶ An idempotent order path with a ledger
```
This diagram is a live interactive SVG widget on the site, not a static image. It shows the two forces and their resolutions: the broadcast price problem resolved by a conflated fan-out subsystem, and the exactly-once money/share problem resolved by an idempotent order path with a ledger.

![Two forces diagram: millions watching live prices forces a broadcast that tolerates dropped ticks, resolved by a conflated fan-out subsystem; an order moving real cash and shares forces exactly-once strongly-consistent writes, resolved by an idempotent order path with a ledger](images/d01_forces.svg)

> **Ingest vs egress.** Ingest here splits two ways: incoming price ticks from the market-data feed, and incoming orders from users. Egress splits the same way: outgoing quote updates to millions of viewers, and outgoing routed orders to the exchange. The read side is the firehose; the write side is where correctness lives.

## Key concepts

This section covers the concepts needed to solve this problem — prerequisites for the design work that follows.

### Idempotency keys for safe retries

An **idempotency key** is a client-generated identifier attached to a write request, so that retrying the exact same request never causes it to happen twice. The server stores a mapping from key to the result it produced the first time; if the same key arrives again — because a response was lost, say, or a client retried after a timeout — the server returns the original result instead of creating a new order. This is what makes retrying a risky operation, like placing an order, actually safe: a client that isn't sure whether its request succeeded can simply resend it with the same key.

```text
Request (key=abc) ──▶ key seen? ──no──▶ Create order, store key ──▶ order ──▶ Return order (response lost)

Retry (key=abc) ──▶ key seen? ──yes──▶ Return the same order, no new one
```
This diagram is a live interactive SVG widget on the site, not a static image. It shows the idempotency key flow: a first request creates the order and stores the key, and a retry with the same key returns the same order instead of creating a new one.

![Idempotency key flow diagram: a request with key=abc checked against key seen, creating an order and storing the key on a no, or returning the same order on a yes retry](images/d02_idempotency_key.svg)

### Double-entry ledgers as the source of truth

A **double-entry ledger** records every change in value as a matched pair of immutable entries rather than as an update to a single balance column: a debit on one side and a credit on the other, netting to zero within each asset. A stock buy, for example, writes a cash leg and a share leg as one atomic pair. Because entries are never overwritten or deleted, the ledger is fully auditable — an account's balance in an asset is the sum of its entries, with holds tracked separately — and it is far harder for a bug to silently erase history than with an overwritten balance column.

The diagram contrasts the two shapes: an overwritten balance column keeps only the latest number and loses history, while the ledger appends immutable entries whose running sum *is* the balance.

```text
Double-entry ledger (append-only):
  +1000 cash (deposit)
   -600 cash (buy leg)
    +10 AAPL (buy leg)
  ──▶ balance = Σ entries = 400 cash, 10 AAPL

Overwritten balance column:
  cash = 1000 ──(buy)──▶ cash = 400   (history gone)
```
This diagram is a live interactive SVG widget on the site, not a static image. It contrasts two balance shapes: the append-only double-entry ledger whose balance is the sum of immutable entries, versus an overwritten balance column that loses history.

![Ledger versus overwrite diagram: three immutable entries summing to a derived balance of 400 cash and 10 AAPL, contrasted with a single cash balance column overwritten from 1000 to 400 with history lost](images/d03_ledger_vs_overwrite.svg)

### Sagas for multi-step operations without a distributed transaction

A **saga** breaks a multi-step operation that can't run inside one database transaction into a sequence of local steps, each with a defined compensation that undoes it if a later step fails. An order is exactly this shape: reserve funds, route to an external exchange, then apply fills as they arrive — and if the exchange rejects the order, releasing the reservation is the compensation that undoes the first step. There's no way to wrap "place an order" and "the exchange executes it" in one transaction, since the exchange is an entirely separate system — a saga is what makes the sequence consistent anyway.

**Interactive order-saga widget** (scenario tabs: Clean full fill / Partial fills / Timeout → in-doubt → reconciled / Risk reject; controls "Play" / "Step" / "Reset"): default view shows step 1 of 5 — "Submitted: Order accepted; funds reserved." Stepping through the default (clean full fill) scenario walks the order through its lifecycle to a completed fill; switching to the other tabs shows the saga branching into a partial-fill sequence, a timeout that parks the order in an explicit in-doubt state until reconciliation resolves it, and a risk/venue rejection that releases the reservation instead of completing it.

Try each scenario in the widget above. Notice that a timeout never resolves by guessing — the order goes into an explicit in-doubt state until reconciliation against the exchange's own record settles what actually happened.

> **Key idea.** Idempotency keys make retries safe, a double-entry ledger makes every money movement auditable and hard to silently lose, and a saga is what holds a multi-step order together across a system boundary that can't share a transaction.

## 1. Requirements

### 1.1 Functional requirements

- Stream live quotes for the symbols a user follows, updating in real time.
- Place an order (buy or sell, market or limit) and track its status as it fills.
- Maintain positions and cash balance, updated as orders execute.
- Show order history and current holdings.

### 1.2 Non-functional requirements

- **Correctness of money and shares.** Never double-execute an order, never oversell cash or shares — the single dominating requirement of this entire system.
- **Strong consistency on cash and positions.** A balance always reflects every committed fill; there's no such thing as an eventual overdraft or an oversold share.
- **Durability and auditability.** Every order and every money or share movement is durable and immutable — a regulatory audit trail, never an overwritten balance.
- **Real-time, low-latency quotes.** Prices reach clients within a moment; a stale price leads to a bad trade decision. This path, unlike the order path, tolerates loss — a dropped tick is simply superseded by the next one.
- **Availability at the market open.** The open is a synchronized spike across the whole user base; the system needs to stay up and degrade gracefully rather than drop orders.

### 1.3 The binding constraint

Correctness on the order and ledger path is non-negotiable — money and shares must move exactly once and remain auditable. But the property that actually shapes the architecture is that this is really two subsystems with opposite properties. Execution happens on an external exchange that can't share a transaction with anything in this system, and fills arrive back asynchronously. The read subsystem is huge, soft-real-time, and tolerates loss; the write subsystem is comparatively modest in volume but tolerates nothing at all. The two collide exactly at the trade: a decision made on a best-effort streamed price must produce an exactly-once, strongly-consistent change to cash and shares. That collision is why the two halves are built as genuinely separate subsystems, and why the order path carries idempotency, a state machine, and reconciliation.

## 2. Back-of-the-envelope estimation

**Interactive estimation widget (default inputs):**

| Input | Default |
|---|---|
| Daily active users (millions) | 10M |
| % streaming quotes at the open | 20% |
| Symbols watched / client | 20 |
| % placing orders / day | 5% |
| Orders / trading user / day | 10 |
| Open peak multiplier | 50× |

**Computed outputs:**

| Output | Value | Formula shown |
|---|---|---|
| Quote messages / sec (read) | 40M/s | 2.0M subscribers × 20 symbols |
| Orders / sec at the open (write) | 11K/s | 50× the average of 214/s |
| Ledger writes / sec at peak | 21K/s | 2 entries (cash + position) per fill |

read : write message ratio ≈ 4K : 1. The read firehose runs orders of magnitude higher than the write path, yet the write path carries all the correctness cost — the two halves have completely different scaling drivers.

Assume roughly 10 million daily active users, with about 20% streaming quotes concurrently at the market open — illustrative anchors, not measured facts. That's `10M × 0.20 = 2M` concurrent connections. If each client watches about 20 symbols, updated on average once a second, the push firehose peaks around `2M × 20 × 1 = 40M` quote messages a second.

The order path is far smaller but far spikier. Suppose 5% of users place about 10 orders on an active day: `10M × 0.05 × 10 = 5M` orders a day. Spread over a 6.5-hour session, that's only about 210 orders a second on average — but the open is bursty, so assume, as another illustrative anchor, roughly a 50x peak multiplier — real market-open spikes vary by venue and aren't published — giving around 10,500 orders a second at the actual peak. Each execution writes an immutable matched ledger pair — a cash leg and a position leg — so the peak ledger write rate is at least `10,500 × 2 = 21,000` writes a second, comfortably within reach of a sharded transactional store.

> **Key idea.** The read (price) subsystem runs orders of magnitude higher in raw message volume than the write (order/ledger) subsystem, yet the write side carries all of the correctness cost — the two halves have completely different scaling drivers, which is exactly why they're built as separate systems.

## 3. API design

**Design checkpoint widget:** *"A user's buy request times out. They tap 'retry.' What has to be true about the request for that retry to be safe?"* Options presented: (a) *Nothing special — retrying a failed request is always safe*; (b) *The request carries an idempotency key, so the retry returns the original order's real status instead of placing a second one*. (Correct: option (b), per the surrounding text on idempotency.)

### 3.1 Get quotes (snapshot)

`GET /quotes`

**Request & response (expanded):**

Request body: `?symbols=AAPL,MSFT`

Response body: `{AAPL: {...}, MSFT: {...}}`

### 3.2 Stream quotes

`GET WS /stream`

**Request & response (expanded):**

Request body: `subscribe {symbols}`

Response body: `pushed quote updates`

The quote stream is a push subscription, not a polling loop. A client opens a persistent connection, subscribes to a set of symbols, and the server pushes updates as they happen; the `GET /quotes` snapshot only seeds the initial screen before the stream takes over. Polling per user for a value that changes many times a second is exactly the wrong shape here.

### 3.3 Place an order

`POST /orders`

**Request & response (expanded):**

Request body: `{idempotency_key, symbol, side, type, qty, limit_price?}`

Response body: `{order_id, status: 'submitted'}`

The idempotency key is what makes a retry safe. `POST /orders` carries a client-generated key; the server maps that key to an order on first use and returns the *same* order for any retry, so a lost response can never turn into a second buy. The order returns `submitted`, not `filled` — execution happens later at the exchange, so the client tracks status until fills actually complete.

### 3.4 Get order status

`GET /orders/{id}`

**Request & response (expanded):**

Response body: `{status, filled_qty, avg_price}`

### 3.5 Cancel an order

`DELETE /orders/{id}`

**Request & response (expanded):**

Response body: `{status: canceling | canceled | filled}`

Cancel is first-class and races with fills. A cancel may arrive while the order is already filling at the exchange, so it isn't guaranteed to succeed — it *requests* cancellation, and the terminal state might still turn out to be `filled`. Cancel itself is idempotent, exactly like submit.

## 4. Data model

The data model is built progressively, each new entity added because the previous ones can't represent the next requirement.

Start with the one obvious entity: an account with cash to trade.

```
Account
+string account_id
+string user_id
+decimal cash_balance
+string currency
```

But cash alone can't represent what a user *owns*. Holding 10 shares of AAPL isn't cash — it's a per-symbol quantity, one row per stock a user holds. That's a one-to-many relationship the account entity can't carry on its own.

```
Position
+string user_id
+string symbol
+int quantity
+float avg_cost
```

But a trade isn't an instant change to those two entities — it's an intent that plays out over time. "Buy 10 AAPL at market" gets submitted, routed to an exchange, and may fill in pieces seconds later. Account and position can't represent an in-flight trade; the intent needs its own entity with a status that walks a state machine.

```
Order
+string order_id
+string user_id
+string symbol
+string side
+string type
+int qty
+float limit_price
+string status
+int filled_qty
+timestamp created_at
```

But applying a fill has to move cash and shares atomically, and survive a retry or a duplicated fill notification. Overwriting `cash_balance` and `quantity` directly loses concurrent updates and leaves no audit trail, and re-applying a fill delivered twice would silently double-spend. Money and shares instead move as immutable double-entry ledger entries, and applied fills get deduplicated by their own key.

```
LedgerEntry
+string entry_id
+string account_id
+string order_id
+string fill_id
+string direction
+string asset
+decimal amount
+timestamp created_at
```

Finally, a live price isn't any of these. A quote is a fact about a symbol, not about a user — it changes many times a second and is read by everyone, owned by no one. It doesn't belong in the transactional store at all.

```
Quote
+string symbol
+float bid
+float ask
+float last
+timestamp ts
```

Where each entity lives follows from how it's used. `Account`, `Position`, `Order`, and `LedgerEntry` live in a strongly-consistent transactional store, sharded by user, so a fill can write its ledger pair and flip order status in one single transaction. Money fields use fixed-point decimals, never floats, so rounding error can't drift a balance. Balances themselves are derived from ledger entries — or materialized in that same transaction — so they stay auditable and can never silently drift. The quote has no home in that store at all: it's served entirely from the market-data fan-out feed and a latest-price cache, since reading a price is a broadcast read, never a per-user database query.

> **Key idea.** Four of the five entities need strong consistency because they represent real money and shares; the fifth — the quote — deliberately lives nowhere near that store, because persisting a broadcast fact as per-user rows would put a firehose of writes on a system that needs to stay strict and small.

## 5. High-level design

Start with the simplest thing that could work: one database, and the buy button writes a row.

```text
User ──▶ App server ──▶ Database
```
This diagram is a live interactive SVG widget on the site, not a static image. It shows step 0: the simplest possible design, one database and the buy button writes a row.

![Naive design diagram: User to App server to Database](images/d04_naive.svg)

Four things break the moment this faces real trading: millions of clients want live prices that change many times a second, and a database read per viewer for a broadcast value is the wrong shape entirely; placing an order must not oversell cash or shares and must survive retries, yet concurrent orders on one account and a retried submit both threaten that; orders don't execute inside this database at all — they route to an external exchange and fill asynchronously, sometimes in pieces; and balances must be provable and reconcilable, since a lost or double-applied fill is someone's real money. Fix them one at a time.

### Fix 1: a market-data fan-out subsystem, separate from the transactional path

A market-data feed publishes ticks into a pub-sub layer; a fleet of streaming gateways hold the millions of persistent client connections and push per-symbol updates to subscribers. This subsystem is read-heavy and soft-real-time, and it tolerates loss — the same broadcast fan-out shape as a chat system or a social feed.

```text
Market-data feed [NEW] ──▶ Pub-sub by symbol [NEW] ──▶ Streaming gateways [NEW] (hold client connections) ──▶ Clients
                                              (subscribe symbols, push quotes)
```
This diagram is a live interactive SVG widget on the site, not a static image. It shows step 1: a market-data fan-out subsystem, separate from the transactional path — feed to per-symbol pub-sub to streaming gateways holding client connections.

![Fix 1 diagram: Market-data feed to Pub-sub by symbol to Streaming gateways holding client connections, subscribing symbols and pushing quotes to Clients](images/d05_fix1_fanout.svg)

### Fix 2: an order service behind an idempotency gate, writing a strongly-consistent store

The client's idempotency key is checked first: a seen key returns the existing order, a new key creates one. The cash-or-shares check-and-move runs under a per-account transaction, so two concurrent orders can never both spend the same dollars.

### Fix 3: an order router and an order state machine

On placement, the order service *reserves* funds or shares — a hold, much like a card authorization — then the router sends the order to the exchange; fills arrive asynchronously on an execution feed and drive the state machine through submitted → routed → partially filled → filled/canceled, with each fill applying to the ledger. This lifecycle is a saga: reserve, route, apply fills, with a compensation (releasing the hold) if the order is canceled or rejected.

```text
Client ──▶ Order service [NEW] (+idempotency gate) ──(idempotency_key / reserve, order row)──▶ Transactional store
                  │
                  ▼
           Order router [NEW] ──▶ Exchange ──(async fills)──▶ Fill handler [NEW] ──(debit+credit+status, one txn)──▶ Transactional store
```
This diagram is a live interactive SVG widget on the site, not a static image. It shows step 3: an order service with an idempotency gate writing reservations to the transactional store, an order router sending to the exchange, and a fill handler applying async fills atomically.

![Fix 3 diagram: Client to Order service with idempotency gate reserving into the Transactional store, then Order router to Exchange to Fill handler applying debit, credit, and status back to the Transactional store](images/d06_fix3_saga.svg)

### Fix 4: the ledger as source of truth, plus reconciliation

Each fill writes the immutable double-entry pair and flips order status in one transaction; balances are derived. Because there's no distributed transaction across the exchange boundary, a periodic reconciliation job compares this system's ledger against the exchange's execution reports, resolving anything left in-doubt.

Composing all four fixes gives the full design — two subsystems side by side:

```text
Orders + ledger (write-consistent):
Client ──(idempotency_key)──▶ Order service + idempotency gate ──▶ Order router ──▶ Exchange
                                                                                       │ execution reports / async fills
                                                                                       ▼
                                                                                Fill handler ──▶ Reconciliation
   all writing to: Accounts · positions · orders · ledger (strongly consistent, sharded by user)

Market data (read-heavy fan-out):
Market-data feed ──▶ Pub-sub by symbol ──▶ Streaming gateways ──(subscribe, push quotes)──▶ Clients
```
This diagram is a live interactive SVG widget on the site, not a static image. It shows the composed design: the write-consistent orders + ledger subsystem alongside the read-heavy market-data fan-out subsystem.

![Composed architecture diagram: two panels — orders and ledger (Client through Order service, Order router, Exchange, Fill handler, Reconciliation, all writing to the sharded Accounts/positions/orders/ledger store) above market data (Market-data feed to Pub-sub by symbol to Streaming gateways to Clients)](images/d07_composed.svg)

These stores are the data model's homes made concrete. `Account`, `Position`, `Order`, and `LedgerEntry` live in the transactional store, sharded by user; the quote deliberately has no home there — it's the market-data feed plus a latest-price cache. The order service writes the order and reservation on placement, the fill handler writes the ledger pair and status on each fill, and reconciliation corrects anything left in-doubt. The seam between the two halves is the trade decision itself — the read path delivers the price a user acts on, and the write path records the consequence.

> **Key idea.** Each fix traces to a concrete failure of the naive single-database design — broadcast reads on the wrong shape, unsafe concurrent writes, an un-transactable external exchange, and unprovable balances — not to a feature checklist.

## 6. Deep dives

### 6.1 Market-data fan-out

> **Design checkpoint.** *"One AAPL tick must reach every client watching AAPL, and a liquid symbol ticks many times a second. If every tick were pushed to every subscriber, what breaks first?"* Options: (a) *Storage — too many quotes to keep*; (b) *The push rate itself — subscribers × symbols × tick rate quickly exceeds what any client (or human) can even process*. (Correct: option (b), confirmed by the surrounding text.)

Prices are broadcast, so they fan out through pub-sub rather than per-user reads: the feed publishes each symbol's updates to a per-symbol topic, and streaming gateways subscribe on behalf of the clients connected to them, pushing updates down persistent connections. This is fan-out on the read side — one event reaching many recipients — the same shape shared with a live chat system or a social feed.

The naive firehose is subscribers times symbols times tick rate, which quickly becomes unreadable and unaffordable. A human can't process hundreds of updates a second anyway, so gateways **conflate**: keep only the latest quote per symbol per client, and push at a bounded rate. Conflation is exactly what makes this fan-out affordable — it collapses an unbounded tick rate into a fixed per-client update rate, and it's safe precisely because a stale quote is instantly superseded by the next one.

Millions of connections still need to map to the right gateway, which needs a connection registry, and the symbol universe needs sharding across the pub-sub layer so a hot symbol — a stock everyone happens to be watching — doesn't concentrate all its fan-out cost on one node. A hot symbol is effectively a hot key: replicating its topic across multiple gateways spreads that cost back out.

```text
Market-data feed (raw ticks, many/sec)
        │
        ▼
Per-symbol topics (sharded):
   AAPL (hot) ──▶ replicated across Gateway A and Gateway B
   MSFT (normal) ──▶ one topic, on Gateway C
        │
        ▼
each gateway: conflate (latest per client, bounded rate) ──▶ viewers
```
This diagram is a live interactive SVG widget on the site, not a static image. It shows hot-symbol replication and conflation: a hot symbol's topic is replicated across multiple gateways, and each gateway conflates ticks to a bounded per-client rate before pushing to viewers.

![Hot-symbol replication diagram: Market-data feed into per-symbol topics, with hot symbol AAPL replicated across Gateway A and Gateway B while normal symbol MSFT sits on one topic on Gateway C, each gateway conflating to a bounded rate before pushing to viewers](images/d08_conflation.svg)

If a gateway dies, its connections drop; clients simply reconnect to another gateway, re-subscribe, and receive a fresh snapshot to resync — there's no durable replay on this display path, because the snapshot supersedes old quotes; historical needs like charts and alerts consume the feed through separate pipelines. Under overload, the system degrades by widening the conflation window (fewer updates per second) rather than dropping connections outright. The signals worth watching: quote staleness (the age of the latest pushed price), fan-out lag, connection count per gateway, and reconnect storms at the market open.

> **Strong-answer criteria.** A strong answer names conflation as the mechanism that bounds the push rate, adds a connection registry plus symbol sharding with hot-symbol replication, and degrades under overload by widening the conflation window rather than dropping connections or lying about a price.

**Market-data fan-out: how it's graded**
- **Bad** — Poll a prices endpoint per client, or push every tick to everyone
- **Good** — Pub-sub per symbol to streaming gateways over persistent connections
- **Great** — Conflation, connection registry, symbol sharding, snapshot-on-reconnect

### 6.2 The order lifecycle and the exactly-once ledger

> **Design checkpoint.** *"Your order service submits a buy to the exchange and the call times out. The trade may or may not have happened. What state should the order go into?"* Options: (a) *Assume it failed and let the client resubmit*; (b) *Assume it succeeded and credit the shares*; (c) *Neither — hold it as in-doubt until reconciliation determines the true outcome*. (Correct: option (c), confirmed by the surrounding text.)

Treat an order as a saga from the start: placement reserves funds (for a buy) or shares (for a sell) — a hold, much like a card authorization — then routes to the exchange, and fills apply against that reservation. Each step has a defined compensation: cancel or reject releases the hold. There's no two-phase commit possible across the exchange, so the lifecycle reaches consistency through the state machine plus compensations, not through a distributed transaction.

Idempotency end to end is what makes retries and duplicate fills safe. The client's key dedups submits at the gate; fills are deduped by a venue-scoped key — venue plus execution id plus order id — so a re-delivered execution report applies once; corrections and busts arrive as their own events, never as silent edits. The effect is engineered from deduplication, not bought from a distributed transaction the exchange simply doesn't offer.

**The same saga widget from Key concepts reappears here** (scenario tabs: Clean full fill / Partial fills / Timeout → in-doubt → reconciled / Risk reject; Play / Step / Reset; default shown: step 1/5, "Submitted: Order accepted; funds reserved.").

The ledger is the source of truth, and balances are always derived from it. On each fill, the fill handler writes the immutable matched pair — a cash leg and a position leg, cash out and shares in on a buy, the reverse on a sell — and flips order status, all in one transaction. The global invariant this rests on is that every fill writes a complete matched pair atomically, keyed by `fill_id`: an orphaned leg, a cash move with no paired position move or vice versa, means a fill was lost or double-written somewhere.

```text
Fill event (fill_id) ──▶ fill_id seen? ──┬── yes ──▶ No-op (already applied)
                                          └── no ───▶ One atomic transaction {
                                                          Cash leg (debit/credit)
                                                          Position leg (shares in/out)
                                                          Order status → partially/filled
                                                       } ──▶ Balances derived = sum of legs
```
This diagram is a live interactive SVG widget on the site, not a static image. It shows one fill's atomic transaction: a duplicate fill_id is a no-op, while a new fill writes the cash leg, position leg, and order status in one atomic transaction.

![Fill atomic transaction diagram: a fill event checked against fill_id seen, a duplicate is a no-op already applied, a new fill_id writes the cash leg, position leg, and order status in one atomic transaction feeding balances derived as the sum of legs](images/d09_fill_atomic.svg)

The single most important rule here is to never guess an unknown outcome. If a submit or a fill query times out, the order goes in-doubt — neither filled nor failed. Guessing "filled" risks crediting shares that were never actually bought; guessing "failed" and resubmitting risks a genuine double-execution. Reconciliation against the exchange's execution report is what settles it (deep dive 6.3). The signals worth watching: the count and age of in-doubt orders, and any ledger-versus-exchange mismatch surfacing after reconciliation.

> **Strong-answer criteria.** A strong answer treats orders as a saga with explicit compensations, dedups fills by `fill_id` rather than trusting exactly-once delivery from the exchange, commits the ledger pair and status atomically, and refuses to guess an in-doubt outcome under any circumstance.

**Order lifecycle and ledger: how it's graded**
- **Bad** — Update balances inline when the exchange call returns, and resubmit on timeout
- **Good** — A reserve-then-fill saga, idempotent submits, a transactional double-entry ledger
- **Great** — Fill dedup by fill_id, explicit in-doubt state, reconciliation as the alarm

### 6.3 Concurrency on the account and the exchange round-trip

> **Design checkpoint.** *"A user with $1,000 of buying power fires two $600 market buys in the same instant. What has to be true so they can't both succeed?"* Options: (a) *Nothing extra — the database's default behavior handles this*; (b) *The reservation must be an atomic conditional update on the account row, checked and applied inside one transaction, so only one order can win the funds*. (Correct: option (b), confirmed by the surrounding text.)

Two concurrent orders that each independently check "enough cash?" can both pass that check and both spend the same dollars — the classic lost-update race. The fix is to make the reservation an atomic conditional move on the account row (something like `reserve WHERE available >= amount`) inside the placement transaction, so the database itself serializes it and only one order can actually win the funds. This is the same per-item serialization pattern used for a seat hold in a ticketing system or a bid on an auction item — here the contended item is simply the account's buying power, or a position's share count for a sell.

**Interactive seat-hold-race widget** (scenario tabs: "Race for one seat" / "Hold expires, seat reopens"; Play / Step / Reset): default state — "t0 — 5 requests arrive in the same millisecond"; seat 14A status: available; five contenders (Alice, Bob, Carol, Dave, Eve) all shown as "pending", each having sent `POST /events/{id}/holds` for seat 14A at once. Stepping through resolves to exactly one contender winning the seat and the rest being rejected; the second tab shows a granted hold expiring and the seat reopening for the next contender.

The widget above demonstrates the identical mechanism with a different contended resource: five simultaneous requests race for one seat, and the database's atomic conditional update lets exactly one win. Read it side by side with the account case: "seat 14A" stands in for the account row itself, and each of the five contenders stands in for one of the two competing $600 buy requests — only one request's conditional update actually matches and wins the funds, exactly like only one contender wins the seat. Swap "seat" for "buying power" and the race, and its resolution, are the same.

Because the transactional store is sharded by account, each account's writes land on one shard. With a single leader per shard and the reservation's row-level locking, that shard serializes the account's orders; a very active account becomes a hot shard, mitigated mainly by keeping each account's per-order work small and fast. A reservation is tied to a live order and released on fill, cancel, or expiry — though a resting limit order can legitimately hold buying power for as long as it stays open on the exchange.

After routing, fills stream back on an execution feed, and each one advances the order — partial fills accumulate into `filled_qty` — while applying to the ledger against the reservation, truing up the held amount to the actual fill price. A cancel races the fills directly: it requests cancellation at the exchange, and the terminal state is whatever the exchange ultimately confirms — either the remainder cancels, or a late fill wins and the order completes anyway.

```text
Submitted ──(sent to exchange)──▶ Routed ──┬──(risk / venue reject)──▶ Rejected
                                            ├──(partial fill)────────▶ PartiallyFilled ──(more fills)──▶ PartiallyFilled
                                            │                                          └─(remainder fills)──▶ Filled
                                            ├──(full fill)───────────▶ Filled
                                            └──(cancel confirmed)────▶ Canceled ◀──(remainder canceled)
```
This diagram is a live interactive SVG widget on the site, not a static image. It shows the order status state machine: submitted routes to the exchange, then branches to rejected, partially filled (looping until filled), filled, or canceled.

![Order status state machine diagram: Submitted to Routed, branching to Rejected on risk/venue reject, PartiallyFilled on partial fill (looping on more fills, advancing to Filled on remainder fills or Canceled on remainder canceled), Filled directly on full fill, and Canceled on cancel confirmed](images/d10_order_state_machine.svg)

Reconciliation remains the irreplaceable backstop for everything a compensation can't resolve. Timeouts leave orders in-doubt with no local action able to fix them; a periodic job matches this system's ledger against the exchange's execution reports, finalizing in-doubt orders and catching any drift. The signals worth watching: stuck orders (age in a non-terminal state), reservation-release failures, and post-reconciliation mismatches.

> **Strong-answer criteria.** A strong answer serializes contention with an atomic conditional update scoped to the exact contended resource, trues up partial fills against the original reservation, resolves cancel-versus-fill races by deferring to the exchange's own confirmation, and leans on reconciliation as the backstop for anything a compensation genuinely cannot fix.

**Concurrency and reconciliation: how it's graded**
- **Bad** — Check the balance, then write it; assume the exchange fills instantly and completely
- **Good** — An atomic per-account reservation, fills driving a state machine, a saga with compensation
- **Great** — Serialized contention, trued-up partial fills, exchange-confirmed cancel races, reconciliation

## 7. Variants

**10x scale.** Both halves grow along their own axes. The read fan-out scales by connections and symbols — more gateways, more aggressive conflation, and hot-symbol topic replication, since message rate, not storage, is the actual pressure. The write path scales by orders per second at the open — shard the ledger further by account, keep per-account writes serialized and fast, and run reconciliation incrementally through the day rather than as one end-of-day sweep. The open remains the spike worth designing for above all else: admission control and queueing at placement protect the transactional store the same way a virtual waiting room protects a high-demand ticket sale.

**Market open, halts, and circuit breakers.** The open is a synchronized order spike and a reconnect storm on the price feed happening at the same time. The order path sheds load by queueing placements — never dropping an already-accepted order — while the market-data path degrades by widening conflation. A trading halt or circuit breaker on a symbol is a control event touching both halves at once: stop routing that symbol's orders and mark them accordingly, and flag its quote as halted, a domain-specific state the order state machine has to carry explicitly.

**Extended asset classes.** Options, crypto, and margin change the reservation and risk math rather than the underlying machinery. Margin turns buying power into a computed value instead of plain cash; options add multi-leg orders; crypto changes the venue and settlement details. But the two-subsystem split, idempotent orders, double-entry ledger, and reconciliation stay unchanged throughout — only what gets reserved, and how it's valued, actually grows.

## 8. The transferable pattern

A trading app is two systems with opposite needs bolted together — a read-heavy price broadcast and an exactly-once order/ledger path — and the real skill is keeping them apart rather than trying to unify them. The price feed is a fan-out system with conflation, because a stale quote is superseded within moments; the order path is a ledger system with idempotency and reconciliation, because a lost or doubled trade is never harmless.

The same shape recurs anywhere a best-effort real-time view drives a consequential, auditable action: sports betting (live odds feeding placed bets), ride pricing (a surge display feeding a committed fare), ad exchanges (live bids feeding billed spend). Recognizing that this problem is "fan out a lossy price stream to millions, and separately move money and shares exactly once against an exchange you can't transact across" is what turns an intimidating whole into a conflated pub-sub firehose and an idempotent, reconciled ledger.

## Review

A trading system is really two subsystems built apart on purpose. Market-data fan-out streams live prices through pub-sub to streaming gateways, conflating updates so the push rate stays bounded and tolerating dropped ticks since a stale price is instantly superseded. The order and ledger path is the opposite: every order carries an idempotency key so retries are safe, placement reserves funds or shares atomically to prevent concurrent overspending, and every fill writes an immutable double-entry ledger pair alongside the order's status change in one transaction. Because the external exchange can't share a transaction with this system, an unresolved timeout goes into an explicit in-doubt state rather than ever being guessed, and a periodic reconciliation job against the exchange's own execution reports is the backstop that resolves it.

## Quiz

**Robinhood — check your understanding widget** ("Hide All" / "Reveal All" toggle) — 5 questions, each with a "Show/Hide Answer" button. Full text of every question and its revealed answer:

**1) Why are market-data fan-out and the order/ledger path built as two separate subsystems instead of one unified system?**
They have opposite requirements: the price feed is enormous in volume, soft-real-time, and tolerates loss (a dropped tick is superseded by the next one), while the order path is comparatively small in volume but must be strongly consistent and tolerate nothing — a lost or doubled trade is real money. Building them apart lets each scale and fail according to its own actual requirements.

**2) What does conflation do, and why is dropping intermediate price ticks safe here but dropping an order update would not be?**
Conflation keeps only the latest quote per symbol per client and pushes at a bounded rate, collapsing an unbounded tick rate into a rate a client can actually handle. This is safe for prices because a stale quote is immediately superseded by the next one and carries no lasting consequence — unlike an order update, where losing information means losing track of real money or shares.

**3) A buy order's exchange call times out. Why is it wrong to either resubmit it or assume it filled?**
A timeout means the response was lost, not necessarily that the order failed — the exchange may have already executed it. Resubmitting risks a genuine double-execution, while assuming it filled risks crediting shares that were never actually bought. The only safe state is in-doubt, held until reconciliation against the exchange's execution report establishes the true outcome.

**4) How does the system prevent two concurrent orders on the same account from both spending the same dollars?**
The reservation is made an atomic conditional update on the account row inside the placement transaction (checking and reserving funds in one step), so the database itself serializes concurrent attempts and only one order can win the available funds — closing the read-then-write window where a lost-update race would otherwise occur.

**5) Why does a double-entry ledger make balances more trustworthy than directly updating a cash_balance column?**
A double-entry ledger records every change as an immutable matched pair of entries that are never overwritten or deleted, so the current balance is simply the sum of everything that ever happened — fully auditable and very hard to silently corrupt. Directly overwriting a balance column loses that history and makes concurrent updates far easier to lose or double-apply.

## Sources and further reading

- [Idempotent requests — Stripe API docs](https://docs.stripe.com/api/idempotent_requests) — the idempotency-key contract behind retry-safe order submission, where the same key always returns the same order.
- [Sagas — Garcia-Molina & Salem, SIGMOD 1987](https://sigmodrecord.org/1987/12/09/sagas/) — the original paper on long-lived transactions as local steps plus compensations, the model behind the order lifecycle (reserve → route → fill, with compensation on cancel).

---

### System Design Master Template (embedded video)

YouTube embed: https://www.youtube.com/embed/OWVaX_cBrh8?si=MAfaQS1TV1r7USUI

### Comments

No comments were posted under this page at scrape time — the Comments section shows only the "Post" input with no existing comments listed.

---

## Assets

No downloadable diagram image files exist on the live page — every diagram (the forces diagram, the idempotency/ledger/saga concept diagrams, the market-data fan-out and order-lifecycle architecture diagrams, the order state machine, and the saga/seat-hold-race widgets) is a live JS/SVG widget. The 10 box/arrow diagrams have been recreated as standalone SVG files in `images/`, redrawn to match the live page's box shapes, colors (blue = actor/request, orange = service/process, green = store/success outcome, red = error/failure outcome, amber = decision/hotspot, pink dashed = newly-introduced component), and layout, and embedded inline next to each diagram's text transcription above:

- `d01_forces.svg` — the two-forces diagram ("What makes this problem distinctive")
- `d02_idempotency_key.svg` — request/retry key-seen? flow, creating vs. returning the same order
- `d03_ledger_vs_overwrite.svg` — double-entry ledger entries summing to a balance, vs. an overwritten balance column
- `d04_naive.svg` — 5. High-level design step 0: User → App server → Database
- `d05_fix1_fanout.svg` — Fix 1: market-data feed → pub-sub → streaming gateways → clients
- `d06_fix3_saga.svg` — Fix 3: order service + idempotency gate, order router, exchange, fill handler
- `d07_composed.svg` — the composed design: orders + ledger subsystem alongside market-data fan-out
- `d08_conflation.svg` — 6.1 hot-symbol topic replication and per-gateway conflation
- `d09_fill_atomic.svg` — 6.2 one fill's atomic transaction (dedup, cash leg, position leg, order status)
- `d10_order_state_machine.svg` — 6.3 order status state machine (submitted → routed → rejected/partially filled/filled/canceled)

The interactive widgets without a static box/arrow layout (the order-saga scenario tabs, the seat-hold-race widget, the back-of-the-envelope estimation calculator, and the quiz reveals) remain text-only transcriptions, matching how the ticketmaster reference page handled its own non-diagram interactive widgets. The only real `<img>` on the page is the site's decorative header logo (`/logo.svg`), which is purely cosmetic and carries no article content, so nothing further was retrieved.
