# Design a Metrics Monitoring System

Source: https://systemdesignschool.io/problems/metrics-monitoring/solution

> Note on fidelity: this page is built from many JS-interactive widgets (sliders, step-through diagrams, tabbed panels, animated simulations, expandable "Request & response" and quiz-answer panels) rather than static images. Every widget's full content — including states behind tabs/toggles/expanders, and the labels/boxes/arrows inside each diagram — has been clicked through and transcribed below as text, in the same order it appears on the site. The site has no downloadable diagram image files (they're rendered live by JS/SVG, not `<img>` files), so there are no image assets to save for this page.

Tags: **Hard** · Time-series storage · Cardinality · Alerting

---

## Problem statement

Design a metrics monitoring system. Millions of hosts and services emit numeric measurements — request rates, latencies, queue depth, error counts — and engineers watch them on dashboards and get paged when something breaks.

In scope: ingesting metric points from many sources, storing them as queryable time-series, answering range queries for dashboards, and evaluating alert rules that page someone. Out of scope: log aggregation and distributed tracing (different systems with different storage models), the dashboard visualization frontend, and machine-learning anomaly detection.

## Clarifying questions

- **Metrics only, or logs and traces too?** Numeric time-series only. Log aggregation and distributed tracing solve different problems with different storage; name them and defer.
- **Which metric types?** Counters (a monotonically increasing running total, like total requests served), gauges (a point-in-time value, like current queue depth), and histograms (a distribution bucketed into ranges, used to compute percentiles like p99 latency). The type decides how a rollup aggregates it later, so it matters early.
- **Exact counts, or approximate?** Approximate is acceptable. This is a monitoring system, not a billing system, so summarizing old data into coarser rollups is a fine trade. That single answer separates this problem from Ad Click Aggregator, which counts exactly because its numbers become invoices.
- **Dashboards, alerting, or both?** Both. Range queries feed dashboards; a rule engine continuously evaluates alerts against the same recent data.
- **How long is data kept?** Raw points for days, coarser rollups for months to years, on progressively cheaper storage. Retention tiers are a primary cost lever.
- **Do hosts push metrics, or does the server pull them?** Either works. An agent on each host can push batches, or the server can scrape each host's metrics endpoint on a schedule. Same data, different transport — worth naming as a design choice.

## What makes this problem distinctive

A normal CRUD system writes a moderate rate of rows and reads them back by key. Here the write side is a firehose: millions of numeric points arrive every second, all day, forever, and the system must keep accepting them even while it falls behind on everything else — shedding load only as a last resort when its buffers fill.

The harder trap is cardinality. Every distinct combination of a metric name and its labels (like `host`, `region`, `endpoint`) is its own time-series. The number of distinct series can explode independently of how fast points arrive. A single engineer adding one high-cardinality label in a code deploy can multiply the tracked series by orders of magnitude. That growth hits memory and the index first, before disk. A system sized only for point-volume can still fall over.

The one relief valve is that this is monitoring, not billing: unlike systems that must count exactly, this one is allowed to lose precision as data ages. That trade — approximate and cheap over exact and expensive — is what makes the rest of the design possible.

![The firehose forces two questions](diagrams/firehose-two-questions.svg)

The never-ending firehose of points forces two questions — whether to store forever (too expensive) and whether to track every series (cardinality explosion) — resolved respectively by approximating via rollups plus retention, and bounding cardinality with hard limits.

> **Ingest vs egress.** Ingest is the rate data arrives (points written per second); egress here mostly means the rate dashboards and alert rules read it back. Both are large, but ingest never stops, which is why it drives the architecture.

## Key concepts

This section covers the concepts needed to solve this problem — prerequisites for the design work that follows.

### Metric types: counters, gauges, histograms

A **counter** only goes up — total requests served, total bytes sent — so its useful signal is the rate of change between two points, not the running total itself. A **gauge** is a snapshot of a current value, like queue depth or memory used, and has no notion of "increase since last time." A **histogram** buckets observed values into ranges (say, `0-10ms`, `10-50ms`, `50-200ms`) so that percentiles like p99 latency can be computed later from the bucket counts, something a single average can never recover.

This distinction matters because a rollup (below) has to aggregate each type differently: summing a counter's per-interval increase, averaging or taking the max of a gauge, and merging a histogram's buckets rather than averaging its already-summarized values.

![Metric types roll up differently](diagrams/metric-types-rollup.svg)

Each metric type rolls up differently: a counter sums its per-interval increase, a gauge takes the last value, average, or max, and a histogram merges buckets so percentiles remain computable.

### Series and cardinality

A **series** is one distinct combination of a metric name and its full label set — `http_requests{region=us,status=200}` is a different series from `http_requests{region=eu,status=200}`. **Cardinality** is the number of distinct series in the system. It multiplies combinatorially: a metric with a `region` label across 5 regions and a `status` label across 10 codes is 50 series from one metric name. Add a label whose values are effectively unique per request — a user ID, a request ID — and the series count can jump by millions from a single line of instrumentation code.

Cardinality is the wall this system actually breaks on, more often than raw point volume, because every series needs an entry in an in-memory index and typically an open write buffer.

![Cardinality multiplies combinatorially](diagrams/cardinality-explosion-1.svg)

Starting from one metric name, adding a region label (5 values) yields 5 series, adding a status label (10 values) yields 50 series, and adding a user_id label (50M values) explodes to 50M+ series, exhausting memory.

### Rollups and retention tiers

A **rollup** downsamples raw points into a coarser resolution: raw 10-second samples fold into 1-minute points, which fold into 1-hour points, which fold into 1-day points. A **retention tier** pairs each resolution with how long it is kept — raw for days, 1-minute for weeks, 1-hour for months, 1-day for years — so storage cost per series stays bounded no matter how long the system has been running.

A range query picks the coarsest resolution that still answers it: a 5-minute zoom reads raw points, a year-long dashboard reads 1-day rollups. This is the same lever from the problem statement — trading exactness for scale — made concrete.

The calculator in the estimation section below turns this into numbers: active series and ingest rate as functions of host count, and how a 1-day rollup collapses a year of raw points into one point per series per day.

> **Key idea.** A metric point's *type* (counter, gauge, histogram) decides how it rolls up, but *cardinality* — the count of distinct label combinations — is the quantity that actually threatens to exhaust memory.

## 1. Requirements

### 1.1 Functional requirements

- Ingest metric points — `(name, labels, timestamp, value)` — for counters, gauges, and histograms, from many hosts. A histogram travels as multiple series: one counter per bucket plus a sum and a count.
- Store points as time-series and roll them up into coarser resolutions with retention tiers.
- Answer range queries: a metric, filtered and grouped by labels, over a time window.
- Evaluate alert rules on a schedule and fire notifications when a condition holds.

### 1.2 Non-functional requirements

- **Ingest availability.** The write path must stay accepting points even under load; a slow query or a stuck background job must never block ingest.
- **Bounded storage and query cost.** Storage per series and the cost of a range query must not grow without limit as the system runs forever.
- **Cardinality safety.** The system must survive — and cap — a spike in distinct series without exhausting memory.
- **Alert timeliness.** Rule evaluation runs on a short, predictable cadence, with a fire reaching a notification within roughly a minute.
- **Freshness.** Data written seconds ago must be queryable, since both dashboards and alert rules read the fresh window.

### 1.3 The binding constraint

Ingest availability is non-negotiable — a system that drops metrics is most likely to drop them exactly when something is on fire and they matter most. But storing every raw point forever, and tracking every series a bad label ever produces, would make storage and memory both unbounded. The resolution is the trade named earlier: downsample and age out data by retention tier, and cap the number of series with hard cardinality limits. That trade is available here because this is monitoring — a rounded-off answer to "is the system healthy?" is acceptable — and it is exactly the trade a billing-grade system like Ad Click Aggregator cannot make.

## 2. Back-of-the-envelope estimation

**Interactive estimation widget (default values shown):**

| Input / Output | Value | Note |
|---|---|---|
| Hosts (millions) | 1.0M | |
| Metrics / host | 100 | |
| Sample interval (sec) | every 10s | |
| Bytes / raw point (compressed) | 2 B | |
| Query range (days) | 365d | |
| Active series | 100M | 1.0M hosts × 100 metrics |
| Ingest rate | 10M/s | the write firehose |
| Raw storage / day | 1.73 TB | grows without bound if kept forever |
| Points read per series (365d range) | raw: 3.2M / rollup (1-day): 365 | 3.2M raw points vs 365 rollup points per series over 365 days |

Raw volume tracks the firehose forever; a 1-day rollup answers the same long-range query with one point per series per day. Series count, not sample rate, is what a bad label multiplies.

Using the scale in the widget above as a working example: 1 million hosts, each emitting 100 metrics sampled every 10 seconds, is 100 million active series. At one sample every 10 seconds that's 6 points/minute per series, so `100M × 6 / 60 ≈ 10M points/sec` — a firehose on the order of ten million points every second.

Assume roughly 2 bytes per compressed raw point. Facebook's Gorilla time-series compression paper reports about 1.37 bytes/point in production, using delta-of-delta timestamps and XOR'd value compression; real ratios vary by encoding and data shape. At 2 bytes/point that's about `10M × 86400 × 2 bytes ≈ 1.7 TB/day` of raw data. Keeping raw for a year would be around 620 TB and a single long-range dashboard panel would scan billions of raw points. A 1-day rollup answers the same year-long query by reading one point per series per day instead — 365 points per series over the year, versus roughly 3.15 million raw points each.

Read load has a less obvious source: it's not mostly dashboards. A hundred thousand alert rules, each evaluated every 15 seconds, is `100,000 / 15 ≈ 6,700` rule-evaluations per second — often more read pressure on the store than humans looking at panels ever generate.

> **Key idea.** Rollups and retention bound this system: without them, storage tracks the firehose forever and every long-range query scans raw points; with them, storage is capped per tier and each query reads only the coarsest resolution that still answers it.

## 3. API design

**Design checkpoint widget:** *"A synchronous POST per metric point would need to handle ten million requests a second. What does the write endpoint need that a normal create endpoint doesn't?"* Options: (a) *Nothing special, POST scales horizontally*; (b) *Batching many points per request, and returning before storage finishes*; (c) *A stronger consistency guarantee per point* — the design picks (b).

### `POST /ingest`

**Request & response (expanded):**
- Request body: `[{name, labels: {host, region, ...}, ts, value, type}, ...]`
- Response body: `204 No Content`

Ingest is a batched, fire-and-forget append. An agent on each host buffers points locally and pushes thousands per request; the endpoint durably accepts the whole batch and returns before the data is necessarily queryable, because a synchronous round trip per point at firehose rate is not possible. Push and pull both fit this shape: agents can push batches on their own schedule, or the server can scrape each target's metrics endpoint at a fixed interval. Scraping hands the server control over sample cadence and gets a free liveness signal — a failed scrape marks a target unreachable, a strong hint it is down even when the cause is the network — while pushing suits short-lived jobs that might not exist long enough to be scraped.

### `GET /query`

**Request & response (expanded):**
- Request (query params): `?metric=http_requests&match={region=eu,status=500}&from=...&to=...&step=1h&agg=avg`
- Response body: `[{ts, value}, ...]`

The read is a range query, not arbitrary SQL: a metric name, a set of label matchers, a time range, a `step` (the requested resolution), and an aggregation. The `step` is what lets the server answer from a rollup — a month of data at a 1-hour step reads hourly rollups instead of scanning raw points.

### `POST /rules`

**Request & response (expanded):**
- Request body: `{name, expr, window, for, threshold, severity, notify}`
- Response body: `{rule_id}`

### `GET /rules/{id}`

**Request & response (expanded):**
- Response body: `{rule, state: ok | pending | firing}`

An alert rule is stored configuration: an expression over a metric, a threshold, an evaluation `window`, and a `for` duration the condition must hold before it fires. The server evaluates it on a schedule and exposes its current state.

## 4. Data model

Start with the one obvious entity: a raw point.

- `Point`: `string metric_name`, `timestamp ts`, `float value`

But `http_requests` is not one stream — it's emitted by thousands of hosts at once, and a dashboard needs to slice it by dimensions like host, region, or endpoint. Those dimensions are **labels**; the metric name plus its full label set is what identifies one stream.

- `Point` (revised): `string metric_name`, `map labels`, `timestamp ts`, `float value`, `string type`

But querying months of raw points is far too expensive — a 90-day dashboard panel would touch every 10-second sample. The entity that actually answers a long-range query is a **rollup**: the same series pre-aggregated to a coarser resolution.

- `Rollup`: `string series_id`, `string resolution`, `timestamp window_start`, `map agg_values`

But the real scaling wall is the label set. Every distinct `(name, labels)` combination is its own **series**, and a query filtering on `region=eu` cannot scan every series to find matches — it needs an inverted index (see database indexing): each label value maps to the set of series carrying it.

- `Series` (1) — (many) `LabelIndex`:
  - `Series`: `string series_id`, `string metric_name`, `map labels`
  - `LabelIndex`: `string label_value`, `list series_ids`

An alert rule is neither a point nor a series — it's configuration, with its own lifecycle.

- `Rule`: `string rule_id`, `string expr`, `duration window`, `duration for_duration`, `float threshold`, `string severity`, `string state`

Where each entity lives follows from how it's used. Raw points and rollups go in an append-optimized time-series store, partitioned by time window (so an expired window drops as a whole on retention) and by series (so one series is contiguous on disk) — the same write pattern an LSM-tree is built for. The series/label index lives in its own store, often memory-resident, because its access pattern is set intersection over labels rather than a time-range scan. Rules live in an ordinary low-volume configuration database, read by the alert evaluator.

> **Key idea.** The point defines what a single sample is; the series index defines how many distinct things are being tracked — and that count, not the sample rate, is what exhausts memory first.

## 5. High-level design

Start with the simplest thing that could work: one database, written to directly, queried directly.

![The naive starting point](diagrams/naive-single-db.svg)

The naive starting point: hosts and agents write directly to one metrics database, which is queried directly for dashboards and alerts.

Four problems break this the moment it faces real load: millions of points per second overwhelm one database's write path; long-range queries over raw data are slow and raw storage grows forever; a single bad label can explode the series count and blow out memory; and nothing here evaluates alert rules continuously. Fix them one at a time.

### Fix 1: an ingestion pipeline

Millions of writes per second can't land directly on a queryable store — a slow query or a background job would stall ingest itself. Agents push batches to an ingest tier that appends onto a partitioned log, decoupling "is ingest available" from "is storage keeping up" — the same firehose-onto-a-log move as Ad Click Aggregator. The decoupling holds only while the log has headroom; sustained lag eventually fills it, forcing load shedding or added capacity. Writers consume the log and batch-append into the time-series store, sharded by series so one region's traffic spreads across writers instead of hot-spotting one.

![Fix 1: an ingestion pipeline](diagrams/fix1-ingestion-pipeline.svg)

Agents push batches to a new ingest tier that appends onto a new partitioned log; new writers consume the log, batch, and shard by series into the time-series store.

### Fix 2: rollup jobs and retention tiers

Raw storage growing forever, and every long-range query scanning it, is the second failure. Background jobs periodically read recent raw data and write 1-minute, 1-hour, and 1-day rollups; a retention manager drops raw after days and coarse tiers after months to years. The query layer routes each request to the coarsest resolution whose `step` still covers the range.

![Fix 2: rollup jobs and retention tiers](diagrams/fix2-rollup-retention.svg)

Raw data feeds new rollup jobs producing 1-minute, 1-hour, and 1-day rollup tiers; a new retention manager drops expired data from each tier.

### Fix 3: a series index with cardinality limits

As writers see a new `(name, labels)` combination, they register it in the inverted label index. Left unchecked, one bad label multiplies series count until the index and its write buffers exhaust memory — so the writer enforces a hard cardinality budget per metric or per tenant, rejecting new series past the cap and naming the offending label so the instrumentation can be fixed at the source (deep dive 2).

### Fix 4: an alert-evaluation engine

Nothing so far turns this data into a page. A scheduler runs each rule on its cadence — query the recent window, compare to threshold, track per-rule state — and a notifier dedupes so one condition pages once, not once per evaluator (deep dive 3).

**Composing all four fixes gives the full design:**

![The full composed design: ingest pipeline](diagrams/composed-full-design.svg)

![The full composed design: query and alert paths](diagrams/composed-query-alert.svg)

The full composed design: hosts/agents push or get scraped into the ingest tier and partitioned log; writers batch and shard by series into the time-series store (raw, hot tier), populating the series/label index; rollup and downsample jobs produce 1-min/1-hour/1-day rollup tiers managed by a retention manager; a query layer picks the coarsest resolution that answers dashboards; and a rule config store feeds an alert evaluator (schedule plus rule state) into a notifier that dedupes and routes notifications.

These boxes are the data model's homes made concrete. Raw points and rollups live in the time-series store and rollup tiers; the series/label index is its own store, populated by writers and read by the query layer; rules live in the rule config store. The single "time-series store" from the data model splits into two write paths here — the ingest writers append raw, while rollup jobs write the coarser tiers later as a background process, because raw arrives from the firehose in real time and rollups are produced afterward.

> **Key idea.** Each of the four fixes traces back to a concrete failure of the naive single-database design — the write firehose, unbounded raw storage, cardinality blowup, and the missing evaluation loop — not to a feature checklist.

## 6. Deep dives

### 6.1 Time-series storage: rollups and retention tiers

**Design checkpoint widget:** *"A dashboard shows one host's request rate over a year, and also lets you zoom into a five-minute window. If you stored only raw 10-second points, what breaks? If you stored only daily averages instead?"* Options: (a) *Raw-only is fine, storage is cheap*; (b) *Raw-only makes long queries scan billions of points; daily-only loses the ability to zoom in or compute percentiles*; (c) *Neither matters if the database is fast enough* — the design picks (b).

Raw points append in timestamp order into a write-optimized, time-partitioned store — an LSM-tree-style append-and-compact layout, with each chunk's timestamps and values compressed separately (delta-of-delta timestamps, XOR'd values). It is partitioned by time window so an expired window drops wholesale, and by series so one series stays contiguous on disk.

A background job then rolls raw into 1-minute points, 1-minute into 1-hour, 1-hour into 1-day. The aggregation depends on the metric type from Key Concepts: a counter rolls up by summing its per-interval increase (a rate is derived from that later), a gauge by last-value, average, or max, and a histogram by merging its buckets so percentiles stay computable. Storing a single average instead would permanently lose the ability to compute a percentile later — averaging away the bucket detail is not reversible.

Retention tiers trade resolution for cost: raw kept on hot storage for days, 1-minute rollups for weeks, 1-hour for months, 1-day for years, each tier cheaper than the last. A query reads the finest resolution that still fits a reasonable point budget — a year at 1-day resolution is 365 points per series, versus roughly 3.15 million raw points for the same panel.

![Rollup and retention chain](diagrams/rollup-retention-chain.svg)

Raw 10-second samples (kept days) downsample into 1-minute (kept weeks), then 1-hour (kept months), then 1-day (kept years); a range query picks the coarsest tier that still answers it.

Rollup jobs are async jobs, made idempotent by keying each output on `(series, window, resolution)` so a re-run overwrites cleanly rather than double-counting. A point that arrives after its window already rolled up is a bounded, droppable correction — acceptable because this is monitoring, not billing. The signals that something is wrong here are rollup lag (windows not yet computed), per-tier storage growth, and query latency broken down by resolution; a stuck rollup job degrades long-range dashboards to stale data while recent raw still serves fine, a contained failure rather than a full outage.

> **Strong-answer criteria.** A strong answer rolls up per metric type (so histograms keep percentile-ability), routes each query to the coarsest resolution within a point budget, keys rollup jobs by `(series, window, resolution)` for idempotency, and treats a late-arriving point as a bounded, droppable correction rather than something requiring exact reconciliation.

**"Time-series storage: how it's graded" (expandable rating list):**
- **Bad** — Store every raw point forever, query raw for every range
- **Good** — Downsample into 1-min/1-hour/1-day rollups with retention tiers
- **Great** — Type-aware rollups, idempotent jobs, late points as bounded corrections

### 6.2 High cardinality — the failure mode that actually breaks this system

**Design checkpoint widget:** *"An engineer adds a user_id label to a request-count metric so they can slice traffic by user, and requests come from 50 million distinct users. What breaks?"* Options: (a) *Nothing, labels are just metadata*; (b) *One metric becomes 50 million series, and the label index plus per-series write buffers exhaust memory*; (c) *Query latency goes up slightly* — the design picks (b).

Cardinality — the count of distinct `(name, labels)` combinations — is what a bad label multiplies. A metric with a `user_id` label across 50 million users turns one metric name into 50 million series on its own. Both the inverted label index and the store's per-series write buffers grow with series count. Many time-series stores keep an in-memory write buffer per active series so recent points can batch before flushing to disk, so active-series count sets a large part of the memory floor directly. A cardinality explosion is therefore a memory explosion, and it tends to hit the system well before raw point volume ever would.

![High-cardinality label explosion](diagrams/cardinality-explosion-2.svg)

Adding a region label (5 values) to http_requests yields 5 series; adding a user_id label (50M values) explodes it to 50M+ series, exhausting the index and per-series write buffers.

The defense is layered. First, hard cardinality budgets per metric or per tenant reject new series past a cap and name the offending label, so the person who added it can fix the instrumentation. Second, relabeling at ingest time can strip or aggregate away an unbounded label before it ever reaches the index — drop `user_id`, keep `region`. Third, keep high-cardinality identifiers out of labels entirely; that kind of per-request detail belongs in logs or traces (the deferred systems), keyed by the ID, not fanned out into a metrics series.

Counting series exactly in real time is itself expensive at this scale, so the system approximates. A distinct-counting sketch like HyperLogLog estimates active-series count cheaply, kept per time bucket and merged over the recent window so "active" has a bounded meaning. A separate per-label-name distinct-value count then pinpoints *which* label is driving an explosion: a runaway label typically has values that are each rare (a `user_id` maps to roughly one series apiece), so it shows up as a label name whose distinct-value count is rising fast. This is the same approximate-counting idea Ad Click Aggregator uses to count clicks cheaply, turned inward on the metrics system's own indexing cost.

The failure is usually self-inflicted by a code deploy, and the blast radius on a shared multi-tenant system is broad — one bad label can stall ingest for every tenant sharing that index, which is why the limits are hard rejections, not soft warnings. The signal that this is happening is a dashboard on active-series count and per-metric cardinality, with an alert on the *rate* of new series appearing, since the fix always lives in the instrumentation that produced the label.

> **Strong-answer criteria.** A strong answer enforces hard per-metric or per-tenant cardinality budgets that reject and name the offending label, strips unbounded labels at ingest via relabeling, estimates active cardinality with a sketch rather than an exact count, and routes high-cardinality identifiers to logs or traces instead of metric labels.

**"Cardinality: how it's graded" (expandable rating list):**
- **Bad** — Index whatever labels show up, no limits
- **Good** — Keep an inverted label index and treat cardinality as a real cost
- **Great** — Hard budgets, relabeling, and sketch-based cardinality tracking

### 6.3 Alert evaluation at scale

**Design checkpoint widget:** *"A hundred thousand alert rules are each checked every 15 seconds against the last 5 minutes of data. A metric crosses its threshold for a single scrape, then recovers immediately. Should that page someone?"* Options: (a) *Yes, any threshold crossing should page*; (b) *No — a single-tick spike shouldn't page, only a sustained breach should, and recovery should resolve it cleanly* — the design picks (b).

A scheduler shards rules across evaluator workers — by rule ID or by series — because rule evaluation is itself a heavy, continuous reader of the store, often thousands of queries per second, independent of anything a human is doing. Each rule's evaluation is a small range query over its recent window compared against a threshold.

A single spike shouldn't page anyone. A `for` duration requires the condition to hold across consecutive evaluations before the rule actually fires, moving it from `pending` to `firing`; recovering before that duration elapses resets it back to `ok`. Hysteresis — firing above one threshold but only clearing once the value drops below a lower one — stops a value that's straddling the line from toggling the alert repeatedly.

**Simulation widget:** slider "for-duration (ticks before firing): 3 ticks"; controls "Play" / "Reset"; state readout "t = 0 — metric value = 40 (threshold=80, hysteresis clear=70)"; state track OK / PENDING / FIRING; readout "notification sent: no"; status line "t=0 — metric below threshold, state=ok." The widget scripts a metric through: a sustained breach that holds long enough to fire, a recovery that resolves it, and — depending on the `for` duration set — a single-tick spike that never satisfies the duration and correctly never pages.

![Alert evaluation state machine](diagrams/alert-state-machine.svg)

The alert state machine: OK moves to Pending when a threshold is crossed; Pending returns to OK if it recovers within the for-duration, or moves to Firing if the condition holds that long; Firing returns to OK once it drops below the hysteresis clear line, or otherwise notifies once, deduped, while still true.

Periodic, sharded evaluation creates its own race: a worker crash mid-cycle could skip a rule's evaluation, and a rebalance afterward could cause two workers to evaluate the same tick. Three fixes close that gap. Align evaluations to a fixed schedule grid, so any worker computes the same result for a given tick. Persist each rule's state, so a failover resumes from `pending`/`firing` rather than resetting to `ok`. Dedupe notifications by `(rule, state-transition)`, so two workers firing the same transition still page only once. This is the same exactly-once-*effect* move used in Ad Click Aggregator: evaluation itself can't be guaranteed exactly-once, so the notification is made idempotent instead.

The scariest failure mode is silence, not a false alarm: if a metric stops arriving entirely — a host is down, or ingest itself is broken — a threshold rule simply never fires, because there's no data to breach anything. A dead-man's-switch rule, which pages on the *absence* of expected data rather than a value crossing a line, catches exactly this. The health signals to watch are evaluation lag, the notification dedupe rate, and a heartbeat rule that pages if the alerting engine itself goes quiet.

> **Strong-answer criteria.** A strong answer aligns evaluations to a fixed schedule grid for idempotent per-tick results, persists rule state across a worker failover, dedupes notifications by state transition to prevent double-paging, adds hysteresis against threshold-straddling, and includes a dead-man's-switch so a silently broken pipeline still pages someone.

**"Alert evaluation: how it's graded" (expandable rating list):**
- **Bad** — Evaluate on a timer, notify on every threshold crossing
- **Good** — Debounce with a for-duration, dedupe notifications, shard rules across workers
- **Great** — Schedule-grid alignment, persisted state, hysteresis, and a dead-man's-switch

## 7. Variants

**10x scale.** Ten times the hosts pushes on cardinality before it pushes on raw point rate — more series means a bigger label index and more open write buffers per series, so hard cardinality limits and sharding the index itself by series become mandatory rather than a safety net. Ingest scales by adding log partitions and writer shards; storage is absorbed by the existing rollup and retention tiers, with the coldest tiers pushed onto cheaper object storage. Point rate scales close to linearly with hosts; the series index is what bends first.

**Multi-tenant and very long retention.** Give each tenant its own cardinality budget so one tenant's runaway label can't exhaust a shared index and starve everyone else. Very long retention leans harder on coarse rollups and cheap cold storage — keep raw only for a short recent window, since years of 1-day rollup points cost little to keep around.

**When a metric must be exact.** If a metric feeds billing or a service-level-agreement credit, lossy rollups stop being acceptable, and that stream graduates to the Ad Click Aggregator design: a durable log as the source of truth, idempotent windowed aggregation, and an exact batch recompute. Money is what forces exactness; monitoring is what allows everything else here to trade it away.

## 8. The transferable pattern

A metrics system funnels a write firehose onto an append-optimized store and stays affordable through pre-aggregation — rollups and retention — instead of exact recomputation, and its real scaling wall is cardinality: the count of distinct series, which grows far faster than the raw data itself. Ingest is decoupled onto a durable log so the write path stays available regardless of downstream lag; long-range query cost is bounded by always reading the coarsest resolution that still answers the question; and an evaluation loop turns the same store into alerts, made safe by debouncing and idempotent notification.

It rhymes with Ad Click Aggregator — both funnel a firehose onto a log and aggregate in windows — but Ad Click counts exactly because its output becomes an invoice, while this system trades exactness for rollup scale because its only job is answering "is the system healthy?" Recognizing that "store the metrics" really means "survive a high-cardinality firehose cheaply and evaluate it continuously" is what collapses the design into four pieces: an ingest log, rollup tiers, a series index, and an alert loop.

## Review

A metrics system ingests a firehose of `(name, labels, timestamp, value)` points onto a durable log so writes never stall, then batches them into a time-series store sharded by series. Rollup jobs downsample raw into 1-minute, 1-hour, and 1-day tiers under a retention policy, and every query reads the coarsest resolution that still answers it. The real scaling wall is cardinality — the count of distinct label combinations — capped with hard budgets and estimated with a distinct-counting sketch rather than tracked exactly. An alert evaluator runs rules on a fixed schedule, debounces with a `for` duration and hysteresis, and dedupes notifications so nobody gets paged twice — with a dead-man's-switch to catch the case where data stops arriving entirely.

## Quiz

**Quiz widget ("Metrics Monitoring — check your understanding")** — "Hide All" / "Reveal All" toggle — 6 questions, each with a "Show/Hide Answer" button. Full text of every question and its revealed answer:

**1) Why does a rollup job need to know a metric's type (counter, gauge, or histogram)?**
Each type aggregates differently: a counter sums its per-interval increase, a gauge takes the last value/average/max, and a histogram merges buckets so percentiles remain computable. Storing a single average instead would permanently lose the ability to compute a percentile later.

**2) What is cardinality, and why does it threaten memory before raw point volume does?**
Cardinality is the count of distinct (metric name, label-set) combinations, each one a series. The label index and per-series write buffers grow with series count, so a single high-cardinality label (like a user ID) can multiply memory usage by millions independent of how fast points arrive.

**3) Why is ingest a batched, fire-and-forget POST rather than one synchronous call per point?**
At millions of points per second, a synchronous round trip per point is infeasible. Agents buffer points locally and push thousands per request; the endpoint durably accepts the batch and returns before the data is necessarily queryable yet.

**4) What does the query 'step' parameter actually control, and why does it matter for cost?**
It sets the requested resolution, letting the query layer read from the coarsest rollup tier that still covers the time range — a month-long query at a 1-hour step reads hourly rollups instead of scanning raw points, which is orders of magnitude cheaper.

**5) Two evaluator workers could both fire a rule's transition on the same tick after a rebalance. How is a duplicate page avoided?**
Notifications are deduped by (rule, state-transition), not by evaluation event, so however many workers compute the same firing transition, only one notification goes out. Rule state is also persisted so a failover resumes from the correct pending/firing state instead of resetting.

**6) Why must 'no data at all' be independently alertable, separate from threshold rules?**
A threshold rule can only fire by comparing against data that exists; if a host or the ingest pipeline goes down entirely, there is nothing to compare, so a normal rule stays silent during the worst outage. A dead-man's-switch rule instead pages specifically on the absence of expected data.

## Sources and further reading

- Count-Min Sketch — Cormode & Muthukrishnan — approximate frequency and heavy-hitter estimation in sublinear space, the sketch family used to find the highest-traffic metrics or label values.
- HyperLogLog — original paper — the distinct-value-counting sketch used here to estimate active series cardinality without tracking every series exactly.
- Gorilla: A Fast, Scalable, In-Memory Time Series Database — Facebook — reports ~1.37 bytes/point using delta-of-delta timestamp and XOR value compression, the source for the compression-ratio estimate used above.

