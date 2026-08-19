# Design Uber / Nearby Drivers

Source: https://systemdesignschool.io/problems/uber/solution

> Note on fidelity: this page is built from many JS-interactive widgets (sliders, step-through diagrams, tabbed panels, animated simulations, an expandable quiz, and expandable BAD/GOOD/GREAT rating rows) rather than static images. Every widget's full content — including states behind clicks/toggles, and the labels/boxes/arrows inside each diagram — has been clicked through and transcribed below as text, in the same order it appears on the site. The site has no downloadable diagram image files (they're rendered live by JS/SVG, not `<img>` files), so there are no image assets to save for this page.

Tags: **Hard** · Geospatial · Write-heavy · Hot key · Availability · Consistency

---

## Problem statement

Design the core of a ride-hailing service: drivers continuously report their location, and when a rider requests a ride, the system finds nearby available drivers and matches one.

In scope: location updates, finding nearby drivers, matching one without double-booking, and driver availability. Ride state, payments, and the trip lifecycle are a separate stateful service.

## Clarifying questions

Each answer changes the design, so state it and the assumption it fixes.

- **Driver count and update frequency?** Sets the write rate — the number that drives everything.
- **How fresh must a driver's location be for matching?** A few seconds of staleness is fine, which justifies an in-memory index that is eventually consistent — replicas may briefly disagree but converge — rather than always exact.
- **What's the match objective — nearest, lowest ETA, fairness?** Shapes matching, not the index.
- **What radius and how many candidates?** Sets the geo resolution and candidate-set size.
- **Ride state and payments here?** Usually deferred — the case is location plus matching; the trip lifecycle is a separate stateful service.
- **Global or per-city?** Ride-hailing is naturally geo-partitioned — cities are mostly independent.

## What makes this problem distinctive

Most systems are read-heavy, so the read path is designed first. This one is the opposite, and that inversion drives the main design constraint. Millions of drivers each emit a location update every few seconds, so the location index absorbs an order of magnitude more writes than the rider queries it serves. The design has to be built around the write path, not the read path.

Two more things fall out of that. The data is ephemeral — a location is stale within seconds anyway, so persisting every update to disk is wasted I/O; hold the live index in memory, sharded by geography, and a lost shard refills within one update interval as drivers re-report. And finding "who is near me" must not scan every driver — it needs a geospatial index that turns proximity into a cheap cell lookup, with care at cell boundaries where the nearest driver may sit just across an edge.

![alt text](image.png)
Millions of drivers → ~1M updates/sec (writes) → The location index; Riders → far fewer nearby queries (reads) → same index. Index is "ephemeral: stale in seconds"; caption "writes dominate, not reads".

**Key idea.** Writes dominate and the data is ephemeral, so this is a write-first design over a throwaway in-memory index — the inverse of a read-optimized system, and that inversion drives the main design decisions.

## Key concepts

This section covers the concepts needed to solve this problem — prerequisites for the design work that follows.

### Geospatial indexing

Space is divided into a hierarchical grid of cells (H3 hexagons or geohash squares). Each shard holds a cell → drivers map, so "who is near me" becomes "look up my cell and its neighbors" instead of scanning the fleet. Cell size is chosen near the match radius.

**Geo cell index.** A scheme (H3, geohash, S2) that maps a lat/lng to a fixed-size cell id, so all drivers in an area share a key and proximity queries touch a handful of cells.

![alt text](image-1.png)
```text
lat, lng ──▶ geo cell id ──▶ cell ──▶ drivers
```
lat, lng → geo cell id → cell → drivers.

### Driver location updates

Updates are ephemeral, in-memory writes sharded by cell. A driver moving within a cell is a cheap in-place lat/lng update; only a cell crossing moves them between cell-sets. Most updates are in-place, which is what makes the firehose absorbable.

![alt text](image-2.png)

```text
Update ──▶ "crossed a cell?"
              ├── no  ──▶ in-place lat/lng update
              └── yes ──▶ move between cell-sets
```
Update → "crossed a cell?" → no → in-place lat/lng update; yes → move between cell-sets.

### Nearby search

Look up the rider's cell and its neighbors — H3's k-ring or geohash's eight adjacents — so a driver just across a boundary isn't missed, then refine candidates by real distance (or road-network ETA) and rank.

Cell size is a tradeoff against the search radius, and it is why "choose a cell near the match radius" matters. For a 1 km match radius, cells roughly 500–600 m across mean the rider's cell plus one ring of neighbors (the center hex plus its 6 adjacents) reliably covers the radius, and you scan only those candidates. Make the cells too large — say 2 km — and a single cell already overshoots 1 km, dragging in far-off drivers you must then distance-filter. Make them too small — say 100 m — and one ring no longer reaches 1 km, so you must query several rings: more lookups and more lists to merge. Sizing the cell near the radius keeps a query to a center cell plus one neighbor ring.

```text
Rider cell ──▶ cell + neighbor cells ──▶ candidate drivers ──▶ refine by distance / ETA
```
Rider cell → cell + neighbor cells → candidate drivers → refine by distance / ETA.

### Matching

The index is eventually consistent and may hand the same driver to two riders, so the match must atomically reserve the chosen driver. Concretely: riders R1 and R2 both read driver D7 as available from the slightly-stale index, and both try to reserve. Each issues a conditional update against D7's single source of truth — `UPDATE drivers SET status='reserved' WHERE driver_id='D7' AND status='available'`. The database applies them one at a time: the first flips the row and reports 1 row changed (R1 succeeds); the second finds status no longer available and reports 0 rows changed (R2 fails). R2's match service reads that zero and moves on to its next candidate, D12. The reservation is linearizable on that one key — concurrent reservations behave as if applied one at a time in a single order — so exactly one rider gets D7 however the reads raced.


![alt text](image-3.png)

```text
R1 match ─┐
          ├──▶ UPDATE D7 to reserved WHERE available ──▶ Driver D7 (source of truth)
R2 match ─┘
                       │                              │
              1 row changed (R1 succeeded)   0 rows changed (R2 failed)
                                                        │
                                                        ▼
                                        R2 retries next candidate D12
```
R1 match and R2 match both → UPDATE D7 to reserved WHERE available → Driver D7 source of truth: 1 row changed (R1 succeeded), 0 rows changed (R2 failed) → R2 retries next candidate D12.

### Hot cells

Assigning cells to shards by consistent hashing (a scheme that maps each cell id to a shard so adding a shard moves only a small fraction of cells) spreads cells evenly, but downtown at peak packs thousands of drivers into one cell — a hot spot in both writes and queries. Use finer resolution in dense areas (smaller cells, fewer drivers each) or split a hot cell across nodes. For example, a downtown cell C may hold 20,000 drivers at peak. Split it into sub-shards C#0–C#3 by hashing driver_id, each on a different node with ~5,000 drivers. A location update for one driver hashes to exactly one sub-shard, spreading the write load four ways. A query for cell C fans out to all four sub-shards in parallel and merges their candidate lists before ranking. The cell stays one logical area; only its storage and write load are split.
![alt text](image-4.png)
```text
Before: one hot cell C, 20,000 drivers, one node

After — split by driver_id hash:
   C#0 ~5k ─┐
   C#1 ~5k ─┤
   C#2 ~5k ─┼──▶ query fans out to all four, merges
   C#3 ~5k ─┘
```
Before: one hot cell C, 20,000 drivers, one node. After — split by driver_id hash: C#0 ~5k, C#1 ~5k, C#2 ~5k, C#3 ~5k; query fans out to all four, merges.

**Key idea.** A geo-cell index makes proximity a cell-plus-neighbors lookup; location is eventually consistent but driver assignment must be strongly consistent.

## 1. Requirements

*Before reading on: List the requirements, then name the property you would never compromise and the constraint that drives the design.*

### 1.1 Functional requirements

- **Update location** — `updateLocation(driver_id, lat, lng)`, very high frequency.
- **Find nearby drivers** — given a rider's location, return available drivers within a radius.
- **Match** — assign one driver to a request and prevent double-assignment.
- **Driver availability** — online / offline / on-trip state.

### 1.2 Non-functional requirements

- **Write throughput** — the location index must absorb millions of updates per second.
- **Match latency** — p99 under a few hundred ms from request to a matched driver.
- **Freshness** — locations stale by seconds are fine; the index is eventually consistent by design.
- **Availability** — matching stays up through node failure; a lost location update is harmless, the next arrives in seconds.
- **Geo-locality** — a city's load is served near that city; regions are largely independent.

### 1.3 The constraint versus the property

Match exclusivity is the property to protect: a driver must never be assigned to two riders, which is why matching is an atomic per-driver lock even though the index around it is loose. The write firehose is the constraint that drives the design: millions of updates per second to data that's stale in seconds forces an in-memory, geo-sharded, durability-free index — a relational store can't absorb it, and disk would be wasted I/O.

**Key idea.** Protect match exclusivity with strong per-driver consistency; design the index around a write firehose of ephemeral data.

## 2. Back-of-the-envelope estimation

The numbers establish that writes dominate and the index is small enough to live in memory. Illustrative anchors.

**Interactive estimation widget (default values shown):**

| Input | Default |
|---|---|
| Active drivers | 5M |
| Location update interval | 4s |
| Ride requests / sec | 50K |
| Bytes / driver in index | 100B |

**Computed outputs:**

| Output | Value | Basis |
|---|---|---|
| Location writes / sec | 1.3M/s | 5M ÷ 4s |
| Ride-request reads / sec | 50K/s | the query side |
| Write : read ratio | 25× writes | writes dominate |
| Live index in RAM | ~500 MB | 5M × 100B |

Formula shown: `writes = 5M drivers ÷ 4s = 1.3M/s, about 25× the reads`. Caption: "Unlike most systems, writes dominate — millions of location updates per second. Size for the firehose; the index is small enough to live in RAM, sharded by geo cell."

### 2.1 The write firehose

At ~5M active drivers each updating every ~4 seconds, that is 5M ÷ 4s ≈ 1.25M location writes/sec. This is the headline number and the reason the index lives in memory.

### 2.2 Reads and memory

Ride requests peak around ~50K/sec — roughly 25× fewer than writes, the inverse of most systems. The live state is about ~500 MB — ~5M drivers × ~100 bytes — small enough to keep in RAM, sharded across cell-owning nodes. Durability isn't needed: a lost shard refills within one update interval as drivers re-report.

**Key idea.** ~1.25M writes/sec versus ~50K reads/sec; ~500 MB of live state — writes dominate, the index is small and rebuildable.

## 3. API design

**Design checkpoint widget:** *"findNearby can return the same available driver to two riders at the same instant. How do you stop both rides from taking that driver?"* Options: (a) *findNearby filters out drivers already returned in the last second*; (b) *Matching atomically flips the driver available→reserved with a per-driver lock/CAS*.

Three operations: the high-frequency write, the read, and the composite that matches.

`GET updateLocation(driver_id, lat, lng, status)`
**Request & response (expanded):**
- Response body: `ok` // high-frequency, no durable ack

`GET findNearby(lat, lng, radius, limit)`
**Request & response (expanded):**
- Response body: `[ drivers ]`

`GET requestRide(rider_id, lat, lng)`
**Request & response (expanded):**
- Response body: `match_id` // find + refine + atomic reserve

**Key idea.** `updateLocation` is a throughput-optimized write with no durable ack; `requestRide` composes find, refine, and an atomic reserve.

## 4. Data model

Three entities, each forced by what its predecessor can't hold.

### 4.1 Trip, location, and the inverted cell index

A durable trip records the ride; an ephemeral driver_location holds the live position; a geo_index inverts it to cell → drivers so proximity is a lookup, not a scan.

- `Trip`: `string trip_id`, `string rider_id`, `string driver_id`, `enum state`
- `DriverLocation`: `string driver_id`, `double lat`, `double lng`, `string geo_cell`, `enum status`, `timestamp updated_at`
- `GeoIndex`: `string cell_id`, `string[] driver_ids`

The trip is durable in a store, persisted for payments and history. `driver_location` and `geo_index` are in-memory and rebuildable, co-located in the sharded location index — no durability needed, since a lost shard refills within one update interval.

**Key idea.** The trip is durable; the live location and its inverted cell index are in-memory, rebuildable state.

## 5. High-level design

The design evolves by addressing each failure mode in sequence.

*Reading the diagrams: each step marks the components newly added at that step with a dashed outline and a NEW badge.*

### 5.1 Locations in a database

Store driver locations in a database; a rider query scans for nearby ones.

![alt text](image-5.png)

It breaks two ways: a relational store can't absorb a million-plus writes per second of data that's stale in seconds (disk I/O wasted on values overwritten immediately), and a proximity scan over the whole fleet is far too slow for a match.

### 5.2 Fix 1: an in-memory, geo-sharded index

Hold the live index in RAM, sharded by geo cell so the write load spreads across nodes in proportion to where drivers are. An ingest gateway routes updates to the cell-owning shard.

![alt text](image-6.png)

Drivers → Ingest gateway **NEW** → Location index shards, in-memory, by cell **NEW**.

### 5.3 Fix 2: the dual cell views

Each shard keeps two co-located maps — cell → drivers (for proximity) and driver → location (for in-place updates) — so a query reads cells and an update touches one driver. For driver D42, starting in cell A: cell[A] lists D42, and driver[D42] = {lat, lng, cell: A}. A small move that stays inside A changes only driver[D42]'s lat/lng — the cell → drivers map is untouched, which is the cheap common case. A move that crosses into cell B is three coordinated edits: drop D42 from cell[A], add it to cell[B], and set driver[D42].cell = B. The driver → location map makes the in-place update O(1); the cell → drivers map makes proximity one lookup — neither map alone serves both, which is why the shard holds both.

![alt text](image-7.png)

*Diagram — Cross from A to B: remove D42 from cell[A], add D42 to cell[B], set driver[D42].cell = B. Move within cell A: driver[D42].latlng updated, cell map untouched.*

### 5.4 Fix 3: matching and the durable trip store

A matching service runs findNearby, refines by distance/ETA, atomically reserves the chosen driver, and writes the ride to a durable trip store — the only thing here that needs to survive a restart. An optional location stream tees updates to pub-sub for ETA and surge models.

![alt text](image-8.png)

*Diagram: Rider → Matching service **NEW** → Location index; Matching service → *(atomic reserve + write)* → Trip store **NEW**; Location index → *(tee)* → Location stream to pub-sub **NEW**.*

**Key idea.** Each component answers one failure: an in-memory geo-sharded index for the firehose, dual cell views for cheap reads and updates, and a matching service with an atomic reserve over a durable trip store.

## 6. Deep dives

### 6.1 Absorbing the write firehose

*Before reading on: A million-plus location updates a second hit the index. Most change very little. What lets the system absorb them, and why is durability not required?*

Hold the index in RAM, sharded by geo cell so the write load spreads with driver density. Most updates preserve the current cell, so they are an in-place lat/lng modification, not an index restructure; only a cell crossing moves a driver between cell-sets. The gateway coalesces updates per shard: within a short batch window it keeps only the latest position per driver, so ten rapid pings from one driver collapse to a single write. Under extreme load it sheds gracefully by dropping the oldest queued updates first once a shard's lag passes a freshness bound (say 2 seconds) — it drops positions already superseded, never a driver's newest one. Durability is unnecessary: a failed shard refills within one interval as drivers re-report, and a lost write costs seconds of staleness on one driver.

*Diagram: Updates → batch per shard → "crossed a cell?" → no → in-place update; yes → move between cell-sets; under overload → sample / drop.*

![alt text](image-9.png)
**What separates answers — the write firehose (expanded BAD / GOOD / GREAT rows):**
- **BAD — Write every update to durable storage.** Persists each update to disk — wasted I/O on data stale in seconds, and the store can't keep up.
- **GOOD — In-memory sharded index, cell-crossing moves only.** Holds the index in RAM, sharded by cell, moving drivers only on a cell crossing.
- **GREAT — In-place vs cross-cell, batching, load-shedding, no durability.** Distinguishes in-place from cross-cell updates, batches per shard with graceful sampling under spikes, and justifies skipping durability via self-healing re-reporting.

### 6.2 Nearby search and matching

*Before reading on: A rider stands right at a cell boundary; the closest driver is just across it. A lookup of only the rider's cell misses that driver. How do you avoid it, and how do you not hand the driver to two riders?*

Look up the rider's cell and its neighbors (H3 k-ring, geohash's eight adjacents), then refine candidates by real distance or road-network ETA and filter out on_trip/offline. Cell size is chosen near the match radius so a query touches only a handful of cells with manageable candidate counts. Matching then atomically reserves the chosen driver — a per-driver lock or CAS flipping available → reserved — so concurrent requests can't both take them; losers retry the next candidate.

![alt text](image-10.png)

*Diagram: Rider → cell + neighbors → candidates → refine by distance / ETA → atomic reserve → succeeded → reserved / failed → next candidate.*

**What separates answers — search and matching (expanded BAD / GOOD / GREAT rows):**
- **BAD — Query only the rider's cell; rank by cell.** Misses boundary-adjacent drivers and never enforces single assignment.
- **GOOD — Cell + neighbors, distance refine, availability filter.** Searches cell plus neighbors, refines by distance, filters availability.
- **GREAT — Resolution from radius, ETA ranking, CAS assignment.** Derives cell resolution from the radius, contrasts straight-line with ETA ranking, and makes the assignment a per-driver CAS over the eventually-consistent index.

### 6.3 Hot cells and match consistency

*Before reading on: Downtown at rush hour packs thousands of drivers into one cell, overloading its shard. And the index can hand one driver to two riders. These need different fixes — what are they?*

Hot cells are a density problem: use finer resolution in dense zones (smaller cells, fewer drivers each) or split a hot cell across nodes. Match consistency is a correctness problem: the index is eventually consistent on purpose, but assignment must be linearizable on a single key, so the reserve is a per-driver CAS — two consistency contracts serving different layers deliberately. Drivers that stop reporting (crash, tunnel) should expire after a few intervals so the system doesn't dispatch to a stale driver record.

![alt text](image-11.png)

*Diagram: Dense cell → Hot cell — a density problem → finer cells / split across nodes. Eventually-consistent index → Assignment — a correctness problem → per-driver CAS, linearizable.*

**What separates answers — hot cells and consistency (expanded BAD / GOOD / GREAT rows):**
- **BAD — Ignores hot cells; assumes one rider per driver.** No hot-cell handling and no enforcement of single assignment.
- **GOOD — Hot cells noted, per-driver locking.** Identifies hot cells and locks per driver on assignment.
- **GREAT — Adaptive resolution, two-contract consistency, stale expiry.** Adapts resolution to density, makes assignment a linearizable CAS while keeping the index eventually consistent, and ages out stale drivers — naming the two-contract model explicitly.

## 7. Variants

For **ETA and routing**, real matching ranks by road-network ETA, not straight-line distance — a routing service over a road graph with live traffic ranks the candidates the geo index found.

For **surge pricing**, surge is a supply/demand ratio per cell over short intervals, computed from the same location firehose as a pub-sub consumer alongside request rates.

For **10× scale**, add more cell-shards, finer resolution in dense regions, aggressive batching/sampling of updates, and per-region clusters — since cities operate independently.

**Key idea.** The geo index and the location firehose feed ETA, surge, and routing as additional consumers; cities shard independently at scale.

## 8. The transferable pattern

When writes dominate and the data is ephemeral, an in-memory index with optional durability becomes the right tool, and proximity reduces to a cell lookup plus refinement. Two consistency contracts coexist on purpose: an eventually-consistent location view paired with a strongly-consistent per-driver assignment, because location staleness is acceptable but assigning the same driver twice is not. The same shape recurs wherever a high-frequency, low-value write stream feeds occasional high-stakes decisions — real-time bidding, fleet telemetry, presence and matchmaking.

### Review: the 30-second answer

- Two flows: a write firehose and a query. Drivers stream location every few seconds; riders occasionally ask "who's near me." Writes far outnumber reads — size for the writes.
- In-memory geo index, sharded by cell. A cell → drivers map turns proximity into a lookup; durability isn't needed because drivers re-report.
- Search the cell and its neighbors, then refine by distance or ETA — so a boundary-adjacent driver isn't missed.
- Match with an atomic per-driver reserve (lock or CAS) so two riders never take the same driver.
- Two consistency contracts: eventually-consistent locations, strongly-consistent assignment.

## Quiz

**Uber / Nearby Drivers Design Quiz** ("Hide All" / "Reveal All" toggle) — 5 questions, each with a "Show/Hide Answer" button. Full text of every question and its revealed answer:

**1) Why is this a write-heavy system, and what does that force?**
Millions of drivers each push a location update every few seconds, so the location index takes on the order of a million-plus writes per second — far more than the tens of thousands of rider requests. That firehose, on data that's stale within seconds, rules out a disk-backed store and forces an in-memory, geo-sharded index where most updates are cheap in-place changes.

**2) Why does the live location index not need durability?**
A driver's location is ephemeral — it's overwritten within one update interval — so persisting it to disk is wasted I/O. If a shard dies, every active driver it held re-reports within a few seconds, refilling the index, and a lost write costs only seconds of staleness on one driver. The only durable thing is the trip record, kept for payments and history.

**3) Why search neighboring cells, not just the rider's cell?**
Cell boundaries are arbitrary, so a rider standing near an edge may have their closest driver just across it in an adjacent cell. Looking up only the rider's cell would miss that driver, so the search includes the cell and its neighbors (an H3 k-ring or geohash's eight adjacents), then refines all candidates by real distance or ETA.

**4) Why must matching use an atomic reserve even though the index is eventually consistent?**
The index can hand the same available driver to two concurrent ride requests, and no amount of read-side care prevents that. Assignment must therefore be strongly consistent on the single driver key: an atomic compare-and-swap flips the driver from available to reserved, so exactly one request wins and the others retry with the next candidate. Location is loose by design; assignment is strict by necessity.

**5) How do you handle a downtown cell packed with thousands of drivers?**
A dense cell is a hot spot in both writes and queries, so it's handled like any hot key but with a geospatial lever: use finer resolution in dense areas so each smaller cell holds fewer drivers, or split a hot cell across multiple nodes. This is a separate problem from match consistency — density is about load distribution, assignment is about correctness.

## Sources and further reading

- *H3: Uber's Hexagonal Hierarchical Spatial Index* — Uber Engineering — the hexagonal cell grid behind the geo index and its k-ring neighbor lookups.
- *H3 documentation* — h3geo.org — cell resolutions, neighbor traversal, and the trade-offs in choosing a cell size near the match radius.
- *Geospatial indexing explained* — Ben Feifke — geohash and grid indexing, the boundary problem, and why proximity becomes a cell lookup.

### Comments (as of scrape date)

- **Adriano Quast** (Feb 07 2025): Thanks the authors for the effort, then works through a scaling sanity-check — in a design interview you might need to justify SQL vs NoSQL for the location store. Using the article's ~1000 QPS figure (Redis-appropriate) and Uber's public ~31M trips/day (~350 trips/sec) figure, estimates read QPS around 3500 and write QPS around 350, and computes bandwidth for geohash+driver-ID reads as roughly 3500 × (32 + 5) ≈ 129,500 bytes/sec (~126.5 KBps) — well within a single Redis instance. Concludes the real scaling bottleneck isn't the database but the ~10 million concurrent WebSocket connections, which don't fit on one server and require horizontal scaling; asks how to deep-dive each part of the solution to confidently prove it works at scale.

---
