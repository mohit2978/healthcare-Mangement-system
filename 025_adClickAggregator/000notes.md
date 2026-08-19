# Design an Ad Click Aggregator

Source: https://systemdesignschool.io/problems/ad-click-aggregator/solution

> Note on fidelity: like the rest of the site, this page is built mostly from JS-interactive pieces (tabbed diagram steps, a late-events simulation widget, design-checkpoint multiple-choice widgets, expandable API request/response bodies, and a quiz with click-to-reveal answers) rather than static images. Everything behind those interactions — the diagram box/arrow labels, the data-model field lists, both API request/response bodies, and all five quiz answers — was expanded live in the browser (via "Reveal All" / "Show Answer" and the request-response toggles) and is transcribed below as text, in the same order it appears on the page. There are no downloadable diagram image files on this page (every diagram is a live JS/SVG widget, not an `<img>`), so no image assets exist to save.

Tags: Hard difficulty · Stream processing · Sharding · Availability · Async processing

---

## Problem statement

Design a system that records ad clicks and gives advertisers near-real-time aggregates — clicks per campaign, sliced by country or device, over a time window — while also feeding the numbers that go on an advertiser's bill.

In scope: ingesting click events, aggregating them into windowed counts, and serving those aggregates to dashboards and a billing job. Click attribution modeling, fraud and invalid-click filtering, and the ad-serving decision itself are separate systems and out of scope.

## Clarifying questions

Each answer fixes an assumption the design leans on.

- **Billing-grade or dashboard-grade accuracy?** Both, with different tolerances. A dashboard can tolerate a few seconds of lag and a rough estimate; a bill cannot be wrong in either direction.
- **What's the query shape?** Aggregates sliced by campaign or ad, over a time window, by a small set of dimensions (country, device) — not arbitrary ad hoc analytics. That constraint is what makes pre-aggregation viable.
- **Peak click rate?** Millions of clicks a second at peak, heavily skewed toward a handful of viral campaigns.
- **How fresh must dashboards be?** Seconds to minutes is fine, which rules out sub-second synchronous counting and opens the door to windowed, asynchronous aggregation.
- **Is fraud or invalid-click filtering in scope?** No — treat it as a downstream filter applied to the same event log, not part of this design.

## What makes this problem distinctive

A naive version of this problem looks like a counter: a click comes in, increment a row. That naive version breaks twice over. First, the network between a browser and this service retries and duplicates constantly — the same click can arrive twice, or the ack for a successful write can get lost and the client resends. A counter has no way to tell "the same click, delivered again" from "a second, distinct click," so it either double-counts or, if you dedupe carelessly, drops a real click. Second, ad traffic is not evenly spread: one viral campaign can pull in a large share of all clicks in the system, concentrating writes onto whatever single counter row represents it while every other row sits idle.

Both problems point at the same tension. The property that cannot be compromised is billing accuracy — a click must be counted exactly once, system-wide, no matter how many times it is retried. The constraint that shapes everything else is that this accuracy has to hold at a scale and skew where no single write path, however careful, can check-then-increment fast enough on one row.

![How do you get exactly-once counting?](diagrams/exactly-once-counting.svg)

"Same click, retried by the network" → one counter must count as ONE; "One viral campaign, outsized share of clicks" → can't hot-spot.

> **Key idea.** Billing accuracy is the property that cannot bend; duplicate deliveries and hot-campaign skew are the two forces that make a naive counter unable to hold it.

## Key concepts

This section covers the concepts needed to solve this problem — prerequisites for the design work that follows.

### The durable log as source of truth

An append-only, partitioned, replayable stream of events — the same mechanism the message queue design builds in depth. Every click is appended and never edited in place; a retried send can append the same event twice — a duplicate the downstream dedup handles. Because the log is retained and replayable, any consumer that reads it wrong, crashes mid-read, or needs to recompute a number can read the same events again and get the same answer. The log, not any downstream counter, is the thing that can never be wrong.

![The durable log as source of truth](diagrams/durable-log-source-truth.svg)

Durable log: Click producer → append → Durable log (partitioned, retained) → replay → Consumer: live aggregation / Consumer: batch recompute.

### Idempotency and delivery guarantees

A network that retries can deliver the same message more than once — this is at-least-once delivery, the practical guarantee for any write path that refuses to drop messages, since a lost acknowledgment forces a sender to retry without knowing whether the first attempt landed. An **idempotency key** — here, a client-generated `event_id` — turns "delivered twice" into "processed once": a consumer that has already applied an `event_id` treats a repeat as a no-op instead of a second increment.

![Idempotency check](diagrams/idempotency-check.svg)

Idempotency check: click, event_id=X → "seen X before?" → no: apply, count += 1 / yes, retry: no-op.

### Windowed aggregation and event time

Counting "clicks per campaign" needs a time boundary — a **window** (a minute, an hour, a day) that groups events for one answer. Each event carries its own **event time** (when the click actually happened), which can differ from **processing time** (when the system saw it). Aggregating by event time keeps a click in the minute it happened even if it arrives late; aggregating by processing time would drift the count toward network delivery timing instead of reality. Windowing patterns covers the general mechanics; the late-events deep dive below covers exactly how a window knows when to close.

![Event time vs. arrival](diagrams/event-time-vs-arrival.svg)

Event time vs. arrival: click, event time 3:59 → window [3:00, 4:00) (arrives at 4:05, i.e. late); click, event time 4:02 → window [4:00, 5:00).

### Speed layer and batch layer

One stream job can produce fast, near-real-time aggregates for dashboards. A separate, slower job can periodically recompute the same windows straight from the durable log and overwrite the fast numbers with exact ones. This split — a fast, slightly-approximate **speed layer** and a slower, exact **batch layer** reading the same log — resolves the tension between "dashboards want it now" and "the bill has to be exact," rather than picking one at the expense of the other.

![Speed layer and batch layer](diagrams/speed-batch-split.svg)

Speed/batch split: Durable log → Speed layer (fast, approximate) → Aggregate store; Durable log → Batch layer (slow, exact) → overwrite → Aggregate store.

### Sharding and hot-key skew

Splitting the log and the aggregation work across partitions spreads load — as long as the partition key spreads evenly. A viral campaign breaks that assumption: every click for it hashes to the same key, so one partition absorbs a disproportionate share of the traffic while the rest sit idle. The general fix — splitting one hot key into several sub-keys and combining them back at read time — is worked out concretely for this problem in the hot-key deep dive.

![Hot-key skew](diagrams/hot-key-skew.svg)

Hot-key skew: Viral campaign (20% of all clicks) → Partition 1 (overloaded); Every other campaign → Partition 2 (idle) / Partition 3 (idle).

> **Key idea.** A durable, replayable log plus an idempotency key turns "delivered twice" into "counted once"; windowing by event time keeps a late click in its real minute; and a speed/batch split resolves fresh-versus-exact without picking one over the other.

## 1. Requirements

> **Before reading on.** List the functional and non-functional requirements, then name the one property you would never compromise and the one constraint that drives the design.

### 1.1 Functional requirements

- **Ingest a click.** Record `(ad_id, campaign_id, timestamp, dimensions)` plus a unique event id.
- **Aggregate.** Roll clicks up into counts by campaign and ad, over minute/hour/day windows, sliced by a few dimensions like country and device.
- **Query aggregates.** Serve those rolled-up counts to dashboards and to the billing job — both read the same aggregates, at different tolerances for freshness.

### 1.2 Non-functional requirements

- **Accuracy.** No lost clicks, no double-counted clicks — a wrong count is a wrong invoice.
- **Ingest availability.** The write path keeps accepting clicks even when aggregation lags, degrading only if the durable log itself is unavailable.
- **Freshness.** Dashboards want results within seconds to minutes, not instantly.
- **Scale.** Millions of clicks a second at peak, concentrated on a handful of campaigns rather than spread evenly.

### 1.3 The constraint versus the property

The property never to compromise is **accuracy**: every click counted exactly once, system-wide. The constraint that drives the design is that accuracy has to hold under constant retries and duplicate deliveries at a skewed, multi-million-per-second scale — which rules out getting accuracy from a per-click distributed transaction and forces it to come from a durable log plus idempotency plus recomputation instead.

> **Key idea.** Accuracy is the property to protect; doing it without a per-click transaction, under retries and skew, is the constraint everything else answers.

## 2. Back-of-the-envelope estimation

**Interactive estimation widget (default inputs):**

| Input | Default |
|---|---|
| Peak clicks / sec | 10M/s |
| Bytes / click event | 100B |
| Active campaigns | 1.0M |
| Hottest campaign's share | 20% |

**Computed outputs:**

| Output | Value | Formula shown |
|---|---|---|
| Raw ingest / sec | 1.0BB/s | 10M × 100B |
| Aggregate rows / day | 1.4B | 1.0M campaigns × 1,440 min |
| Hottest campaign's clicks / sec | 2.0M/s | 20% of 10M/s on one key |
| Ratio: raw log vs. aggregates | 1.0BB vs 1.4B rows | raw events dwarf the aggregates |

ingest = 10M × 100B ≈ 1.0BB/s; one campaign alone can be 2.0M/s. Aggregates are small — one counter row per campaign per minute. The volume to size for is the raw click log, and skew means one campaign's traffic can rival the entire system's average.

### 2.1 Ingest volume

Assume roughly 10 million clicks a second at peak, each event around 100 bytes. That's `10,000,000 × 100 ≈ 1 GB/second` onto the log — the number the ingest path and the log's partition count have to absorb.

### 2.2 Aggregates are tiny by comparison

Assume roughly 1 million active campaigns. One counter row per campaign per minute is `1,000,000 × 1,440 ≈ 1.44 billion` rows a day — but each row is just a few counters, tens of bytes apiece, so a full day of aggregates is on the order of tens of gigabytes total — while the raw log, at roughly 1 GB/second, accumulates on the order of 86 TB a day. The aggregate store is small; the log is the thing that must scale.

### 2.3 Skew, not raw volume, sizes the hot path

If a single viral campaign takes 20% of all clicks, that one campaign alone can be `10,000,000 × 0.20 = 2,000,000` clicks/second — more than most entire systems see in aggregate. Sizing for the average and ignoring skew under-provisions exactly the partition that fails first.

> **Key idea.** The raw click log, not the aggregate store, is what must absorb roughly 1 GB/second at peak — and skew means a single campaign can demand a meaningful fraction of that alone.

## 3. API design

**Design checkpoint widget:** *"A click should never be lost, but the aggregate it feeds may take seconds to update. Should `POST /v1/clicks` block until the click is reflected in aggregates, or return as soon as the event is durably logged?"* Options: (a) *Block until the aggregate update is visible*; (b) *Return as soon as the event is durably appended to the log*. (The page's own "Key idea" below §3 confirms option (b): ingest returns as soon as a click is durably logged.)

### 3.1 Record a click

The caller generates `event_id`, so a retried request is recognized as a duplicate downstream rather than a second click. In practice a click is often a tracked redirect (a `302` to the advertiser's page) with this same idempotent append as a side effect, not a separate API call the client makes deliberately.

`POST /v1/clicks`

**Request & response (expanded):**

Request body:
```
{
  event_id,
  ad_id,
  campaign_id,
  ts,
  dims: { country, device }
}
```

Response body: `202 Accepted`

### 3.2 Query aggregates

The query shape is fixed — a campaign, a window granularity, a time range, one grouping dimension — not arbitrary analytics. That constraint is what makes pre-computing the aggregates viable instead of scanning raw events per query.

`GET /v1/aggregates?campaign_id={id}&window=minute|hour|day&from=&to=&group_by=country`

**Request & response (expanded):**

Response body:
```
{
  results: [{ bucket, dims, count }]
}
```

> **Key idea.** Ingest returns as soon as a click is durably logged, decoupling write availability from aggregation speed; queries are pre-shaped so the read path never touches raw events.

## 4. Data model

### 4.1 Click

The raw fact — what actually happened, once, ever.

Click schema:
```text
Click {
  string event_id
  string ad_id
  string campaign_id
  timestamp ts
  string country
  string device
}
```

### 4.2 Aggregate

The rolled-up answer queries actually read, keyed by exactly what a query asks for.

Aggregate schema:
```text
Aggregate {
  string campaign_id
  timestamp window_start
  enum granularity
  string country
  string device
  long count
}
```

### 4.3 Seen (dedup state)

Not a permanent table — a short-lived record of which `event_id`s a window has already applied, scoped to that window so it doesn't grow without bound.

Seen schema:
```text
Seen {
  string event_id
  string window_key
}
```

### 4.4 Where each entity lives

![Click and Aggregate: entity relationship](diagrams/er-click-aggregate.svg)

Entity relationship: Click (event_id, campaign_id) —many..1— Aggregate (campaign_id, window_start, count).

`Click` rows live on the durable, partitioned log, retained days to weeks so they can be replayed. `Aggregate` rows live in a small, read-optimized time-series store, partitioned by campaign and time. `Seen` lives inside the stream processor's own checkpointed, windowed state — it only has to remember a window's ids until that window closes to late arrivals (covered in the late-events deep dive), not history.

> **Key idea.** The log is the durable fact table; the aggregate store is small and derived; dedup state is scoped to a window and lives with the processor, not as a permanent table.

## 5. High-level design

> **Before reading on.** You already have the durable log, idempotency, windowing, and the speed/batch split from Key concepts. Sketch how a single click flows from the API to a queryable aggregate, and where duplicate detection happens.

> **Reading the diagrams.** Each step marks the components newly added at that step with a dashed outline and a **NEW** badge, so you can see what changed from the step before.

### 5.1 A counter in the database

Start naive: the API server receives a click and runs `UPDATE aggregates SET count = count + 1 WHERE campaign_id = ? AND window = ?` directly against a database.

![Step 1: a counter in the database](diagrams/step1-naive-counter.svg)

Step 1: Client → App server → aggregates table (naive), counter per campaign/window.

Three things break this at scale.

- Millions of concurrent updates against a handful of hot rows overload the database, and a viral campaign's row is the single hottest of all.
- A retried click runs the `UPDATE` twice, so the count is inflated by every duplicate delivery.
- The counter has no memory of individual clicks, so slicing by a new dimension or recounting after a bug is impossible after the fact.

### 5.2 Fix 1: an idempotent append to a durable log

Move the write off the counter entirely. The API server's only job is to append the click, with its `event_id`, to a durable partitioned log, and return once that append is acknowledged.

![Fix 1: an idempotent append to a durable log](diagrams/fix1-idempotent-append.svg)

Step 2: Client → App server **NEW** → append event_id, ... → Durable log **NEW**.

Ingest is now fast, and stays writable as long as the log cluster is healthy, independent of any downstream aggregation. Nothing yet turns that log into a windowed count, and duplicates are still sitting in the log unresolved.

### 5.3 Fix 2: a stream processor with windowed dedup

Add a stream processor that reads the log, groups events into windows by event time, and checks each `event_id` against that window's `Seen` state before counting it — a duplicate delivery increments nothing the second time.

![Fix 2: a stream processor with windowed dedup](diagrams/fix2-stream-processor-dedup.svg)

Step 3: Durable log → Stream processor **NEW** (dedup by event_id, windowed count) ↔ Seen, windowed state **NEW** → Aggregate store.

Duplicates are handled and dashboards can read near-real-time aggregates. But this speed layer alone is only as exact as its windowing and dedup logic in the moment — a crash, a late event past its window, or a processor bug has no independent backstop for billing-grade correctness.

### 5.4 Fix 3: a batch layer recomputes from the log

Add a periodic batch job that reads the same durable log directly and recomputes each window's count from scratch, overwriting the speed layer's numbers in the aggregate store. The recompute applies the same `event_id` dedup and event-time windowing rules — duplicates sitting in the log would otherwise inflate the batch numbers too.

![Fix 3: a batch layer recomputes from the log](diagrams/fix3-batch-recompute.svg)

Step 4: Durable log → Batch layer **NEW** → recompute + overwrite → Aggregate store.

### 5.5 The composed design

![The composed design](diagrams/step5-composed-design.svg)

Composed: Client → App server → Durable log → Stream processor ↔ Seen, windowed state → Aggregate store ← overwrite ← Batch layer; Aggregate store → Query API → Dashboards / Billing job.

Each piece answers one failure of the naive counter: the durable log fixes write overload and gives replay; the stream processor's dedup fixes double-counting; the batch layer fixes what the speed layer's real-time approximations can still get wrong.

### 5.6 Sequence: a click, end to end

![Sequence: a click, end to end](diagrams/sequence-click-end-to-end.svg)

Sequence (Services: Client, App server, Stream processor; Storage: Durable log, Aggregate store): Client → `POST /clicks (event_id=X)` → App server → append event X → Durable log (durable) → App server → `202 Accepted` → Client; Durable log → deliver event X → Stream processor → dedup check X, not seen → increment window count → Aggregate store.

> **Key idea.** Every fix answers one concrete failure of the counter — write overload, duplicate counting, and the speed layer's own blind spots — not a pre-known list of components.

## 6. Deep dives

### 6.1 Exactly-once counting

> **Before reading on.** The stream processor crashes after updating the aggregate store but before committing its log offset. On restart, it re-reads the same events. What stops those events from being counted twice?

The failure mode is a gap between two writes that both need to happen together: applying an event to the aggregate, and recording that the event has been processed (the log offset). If the processor crashes between them, a restart replays events it already applied. Making those two writes **atomic** — committing the offset and the aggregate update as one transactional unit, or storing the offset alongside the aggregate in the same transactional write — closes the gap. The aggregate store has to participate: offset and aggregate must be writable together in one transaction. A replayed event then finds its `event_id` already reflected in the atomically-stored state and is skipped, not re-applied.

The raw log is the backstop underneath that: because every event is retained, a periodic reconciliation job can compare the speed layer's total for a window against a full recompute from the log, and a persistent, unexplained gap between the two is the signal that something in the atomic-commit path is wrong.

![Atomic commit vs. separate writes](diagrams/atomic-vs-separate-writes.svg)

Atomic vs. separate writes: "Atomic commit — replay is a no-op": aggregate update + offset committed as one unit → restart replays event → event_id already reflected — skip. "Separate writes — crash between them double-counts": apply event to aggregate → crash before offset commit → restart replays event — counted twice.

**What separates answers — exactly-once counting:**
- **Bad** — At-least-once and hope duplicates are rare
- **Good** — Dedup by event_id in the aggregator
- **Great** — Atomic offset-plus-aggregate commit, log as recompute backstop

### 6.2 Hot-key skew

> **Before reading on.** A single campaign is suddenly 20% of all clicks in the system. Every one of those clicks hashes to the same partition key. What breaks, and what's the fix that doesn't involve buying a bigger single node?

Partitioning by `campaign_id` spreads load only when campaigns are roughly equal in traffic. A viral campaign breaks that assumption outright: every one of its clicks lands on the same key, so one partition (and the one aggregation task consuming it) absorbs traffic far above what any single node should carry, while every other partition sits comfortably under load.

**Key-salting** splits that one hot key into several: writes append a suffix, `campaign_id:{0..N}`, spreading the same campaign's clicks across N partitions instead of one. The suffix must be deterministic per event — `hash(event_id) mod N`, not a fresh random draw — so a retried event routes to the same salted sub-key and per-partition dedup still catches the duplicate. Each salted sub-key aggregates independently, and a query sums the sub-counts back together — the split is invisible to the reader.

**Pre-shuffle local aggregation** goes further upstream: before an event reaches a shared partition, each stream-job task combines same-key events over a short local interval, so the shared partition sees one `+1,000` instead of a thousand individual `+1`s. The combining happens inside the task's checkpointed state, downstream of the durable append, so a crash replays those events rather than losing them. Detecting which keys need this treatment, rather than salting every key all the time, means tracking per-partition throughput and raising a key's salt factor only once it crosses a threshold — most campaigns never need it.

![Hot-key salting: before and after](diagrams/hot-key-salting.svg)

Before/after salting: "Before — one hot key": campaign_id: viral → 1 partition, overloaded. "After — salted across N": viral:0 / viral:1 / viral:2 → sum sub-counts at query time.

**What separates answers — hot-key skew:**
- **Bad** — Partition by campaign_id, accept the hot spot
- **Good** — Salt the hot key, sum on read
- **Great** — Salting plus pre-shuffle combining, detected and applied adaptively

### 6.3 Late and out-of-order events

> **Before reading on.** A phone goes offline at 3:59, comes back online at 4:05, and its buffered click for 3:59 arrives then. Which window should count it, and how does the system know the 3:59 window is still willing to accept it?

Aggregating by event time (Key concepts) means a late click still belongs to the minute it actually happened, not the minute it arrived. But a window has to close and finalize at some point, or dashboards would wait forever for stragglers. A **watermark** — a marker that trails behind the latest event time seen by some grace period — answers "how long do we wait": a window finalizes once the watermark passes its end, and anything arriving after that is, by definition, late.

A late event has two possible outcomes depending on timing. If it arrives before the watermark has passed its window's end, it lands normally — no special handling needed, since the window is still open. If it arrives after, the window has already finalized and published a number, so applying it is a **correction**: the aggregate store's value for that window changes after the fact. That is why dashboards read the speed layer's live-but-provisional numbers, while billing reads the batch layer's version, computed after the grace period and any later corrections have settled.

**Interactive late-events simulation widget:** sliders "Watermark grace period" (default 2m), "Late-event probability" (default 15%), "Max lateness" (default 3m); controls "Play" / "Reset"; readout "now = 0m, watermark = 0m"; visualization is a row of minute-windows (each a column) where a window can be ● open, ● closed, or ● corrected after close; counters shown: "absorbed late 0", "corrections 0", "dropped 0"; caption: "Processing time starts at minute 0."

**Design checkpoint widget:** *"Widening the watermark's grace period catches more late events before their window closes. What does it cost?"* Options: (a) *Nothing — a wider grace period is strictly better*; (b) *Freshness: every window now waits longer before finalizing, even when most events aren't late*. (Confirmed correct by surrounding prose: option (b), the freshness cost.)

**What separates answers — late and out-of-order events:**
- **Bad** — Count by arrival time
- **Good** — Event-time windows with a fixed grace period
- **Great** — Watermarks plus a batch backstop, tuned from observed lateness

> **Key idea.** Atomic offset-plus-aggregate commits make replay a no-op; salting plus pre-shuffle combining relieve a hot key without a bigger single node; and watermarks trade freshness for how many late events avoid becoming a correction, with the batch layer as the backstop either way.

## 7. Variants

**10× scale.** Ten times the clicks means more log partitions and more stream-processor parallelism, but the real pressure is skew, not the average rate: at higher scale there are more hot campaigns and each is hotter, so key-salting and pre-shuffle combining move from an occasional fix to a default. Raw-event retention, needed for recompute, becomes the dominant storage cost; tiering older raw events to cheaper storage helps, since only recent windows are ever actually recomputed.

**Approximate is good enough.** If the numbers only ever feed dashboards — never billing — the exact batch layer can be dropped entirely in favor of probabilistic structures — count-min sketch for per-key click frequencies, or HyperLogLog where the question is distinct counts, such as unique users rather than raw clicks — trading exactness for a large win in memory and latency. It is specifically the presence of billing that forces this design's exact, fully replayable path; remove that requirement and a much cheaper approximate-only system suffices.

**Real-time-only (kappa architecture).** If the stream layer's exactly-once handling (6.1) is trusted enough on its own, the separate batch codebase can be dropped: corrections happen by replaying the same stream-processing job over the retained log instead of running a second system. This is one codebase instead of two, at the cost of a longer replay window whenever a correction is needed.

> **Key idea.** The architecture holds at 10× scale by making salting the default rather than the exception; only a genuine "no billing" requirement removes the batch layer for cheaper approximate counting, and trusting the speed layer enough collapses it to one codebase (kappa) instead of two.

## 8. The transferable pattern

A counting pipeline is a durable log plus idempotent, windowed aggregation: accuracy comes from the log being the source of truth and from deduping each event once, never from wrapping a per-event transaction around a counter. Ingest and aggregation decouple so writes stay available even when aggregation lags. The fresh-versus-exact tension resolves with two layers reading one log rather than picking one property over the other. Skew is handled by splitting one hot key into several and summing on read. The same shape reappears anywhere high-volume events arrive with duplicates and need a trustworthy count — metrics pipelines, view counters, rate meters, analytics.

## Review: the 30-second answer

- A durable, replayable log is the source of truth; nothing about correctness depends on a per-click transaction.
- An idempotency key (`event_id`) turns a retried delivery into a no-op instead of a double-count.
- A speed layer gives fast, near-real-time dashboards; a batch layer recomputes from the log for billing-grade exactness.
- A hot campaign is handled by salting its key across partitions and summing on read, not by a bigger single node.
- Watermarks decide when a window closes; anything later becomes a correction, backstopped by the batch layer.

## Quiz

**Ad Click Aggregator Design Quiz widget** ("Hide All" / "Reveal All" toggle) — 5 questions, each with a "Show/Hide Answer" button. Full text of every question and its revealed answer:

**1) Why can't a database counter, incremented on every click, satisfy the accuracy requirement here?**
The network between clients and the service retries on lost acknowledgments, so the same click can trigger the write twice; a bare counter has no memory of individual events and so cannot tell a retry from a new click, causing double-counts. It also concentrates all of a viral campaign's writes onto one row, which collapses under load regardless of correctness.

**2) Why does making the offset commit and the aggregate update atomic fix exactly-once counting, when dedup by event_id alone does not?**
Dedup by event_id only helps if the processor's state reflects exactly what's been committed. If a crash happens between applying an update and recording that the source event was processed, a restart replays events whose "already applied" state was never durably saved, causing a double-count. Committing both as one atomic unit means a replay always finds the event's effect already reflected, so re-applying it is a genuine no-op.

**3) A campaign goes viral and takes 20% of all clicks. Why does salting its key fix the hot-partition problem without losing per-campaign accuracy?**
Salting appends a per-event deterministic suffix (such as hash(event_id) mod N) to the hot campaign's key, spreading its clicks across N partitions instead of concentrating them on one, so no single partition or aggregation task is overloaded — and a retried event still lands on the same sub-key for dedup. The split is invisible to readers because a query sums the salted sub-keys' counts back together — the total for the campaign is unaffected, only the physical distribution of the writes changes.

**4) Why does aggregating by event time instead of processing time matter for correctness, and what does a watermark actually decide?**
Aggregating by processing time would put a late click in whatever window happened to be current when it arrived, drifting the count toward network delivery timing rather than reality. Event time keeps a click in the minute it actually happened. A watermark decides when a window is allowed to stop waiting and finalize — anything arriving after the watermark has passed a window's end is treated as a correction rather than a normal, on-time contribution.

**5) Why does removing the billing requirement change the right design so much, per the 'approximate is good enough' variant?**
Billing is what forces exact, fully replayable counting — a wrong invoice is unacceptable in either direction, which is why the design keeps a durable log and a batch layer that recomputes exact numbers. Without billing, the only consumer is dashboards, which tolerate approximation, so a much cheaper probabilistic structure (count-min sketch, HyperLogLog) can replace the exact log-and-batch-recompute machinery entirely.

## Sources and further reading

- [Exactly-once semantics in Kafka — Conduktor](https://www.conduktor.io/glossary/exactly-once-semantics-in-kafka) — the idempotent producer (producer id plus sequence number) and transactions, the mechanism behind atomic offset-and-aggregate commits.
- [Photon: Fault-tolerant and Scalable Joining of Continuous Data Streams — Google, SIGMOD 2013](https://research.google/pubs/pub41318/) — Google's production exactly-once ad-click joining pipeline, the reference design for accurate counting at ad scale.

---

