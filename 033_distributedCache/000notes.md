# Design a Distributed Cache

Source: https://systemdesignschool.io/problems/distributed-cache/solution

> Note on fidelity: this page is built from many JS-interactive widgets (calculators, step-through diagrams, sequence diagrams, ring diagrams) rather than static images. Every widget's full content — including states behind tabs/toggles, the labels/boxes/arrows inside each diagram, and the answers behind the quiz's "Show Answer" buttons — has been clicked through (via "Reveal All") and transcribed below as text, in the same order it appears on the site. The site has no downloadable diagram image files (they're rendered live by JS/SVG, not `<img>` files), so there are no image assets to save for this page.

---

Tags: Hard · Caching · Consistent hashing · Hot key · Availability · Cache invalidation

## Problem statement

Design an in-memory caching layer that sits in front of a primary data store and serves reads at sub-millisecond latency for tens of millions of requests per second. The cache must shard across many nodes, survive individual node loss without taking the database down with it, and offer a defensible answer to "what happens when a stale value is served."

Build it as a general-purpose cache — the same design backs feed metadata, session state, product catalogs, profile lookups, and other cache-backed resources in production systems. Out of scope: HTTP/CDN caching semantics (`Cache-Control`, `ETag`), the primary store's design, and disk-backed persistence (touched only in variants).

## Clarifying questions

- **Read/write ratio and absolute QPS?** Read-heavy versus mixed shifts the write-path and eviction choices.
- **Consistency contract for cached data?** How much staleness is tolerable, and is read-your-writes (a caller immediately seeing its own completed write) required.
- **Working-set size and access distribution?** The working set (the hot fraction of keys that serve most traffic) under a skewed (Zipfian) distribution lets the cache be far smaller than the dataset while keeping a high hit rate.
- **What does a cache miss cost?** A cheap DB hop, or an expensive aggregation that makes misses unacceptable.
- **What does serving stale data cost?** From "slightly stale profile photo" to "catastrophic" for an account balance.
- **Is the cached data derivable or authoritative?** A computed feed can be re-derived on a miss; a user-set preference that lives only in the cache is data loss — this decides write-through (write to cache and store together) versus write-back (write to cache now, flush to the store later).

## What makes this problem distinctive

A cache looks like a hash table with a TTL and nothing more. The design work is everything around that table, and it comes down to three tensions that a plain CRUD service never faces.

The first is **sharding versus routing**. Capacity scales by adding shards, but every request must still find the one shard that owns its key — and keep finding it as shards come and go. The second is that **hit rate is an economics problem**, not a storage one: the cache exists only to keep traffic off the store, so it must be sized for the *working set* (the hot fraction of keys), not the dataset. The third is that **invalidation lives on the write path**: a slow reader refilling old data just after a writer cleared it, cross-region lag, and broadcasting deletes to local caches all stem from how a write fans out to whatever clears the cache.

> **Working set.** The fraction of keys that serve most of the traffic. Under skewed access, a small slice of keys gets most of the reads, so caching that slice yields a high hit rate at a fraction of the dataset's size.

![Distributed cache overview](diagrams/cache-overview.svg)

> **Key idea.** The cache data structure is simple; the design work is sharding for scale, routing for correctness, and invalidation on the write path.

## Key concepts

### Sharding and routing

The cache is split across N nodes by a **consistent hash ring**: nodes sit at fixed points on a hash circle, each key hashes to a point, and a key belongs to the first node clockwise. Adding or removing a node remaps only `~1/N` of keys instead of reshuffling everything. *Routing* is the separate question of how a request reaches that node — the client can hash, a proxy can hash, or the cluster can redirect (the routing deep dive compares them).

> **Consistent hashing.** Keys and nodes are hashed onto one ring; a key is owned by the next node clockwise. Adding a node only steals the arc between it and its predecessor, so only its share of keys moves — not the whole keyspace.

Consider three nodes on a ring at positions 10, 40, 75 — call them A, B, C — and three keys at 12, 50, 88. Each key is owned by the **first node clockwise** from it: key 12 → B (40), key 50 → C (75), and key 88 runs off the top and wraps to A (10). Now add node D at position 30. Only keys that fall in the new arc — just above 10, up to 30 — change owner: key 12 moves from B to D, while 50 and 88 stay exactly where they were. One key in three moved, not the whole keyspace. That is the `~1/N` remap.

**Ring diagram widget:**
- *Before — nodes A@10, B@40, C@75:* key 12 → B (40); key 50 → C (75); key 88 → A (10), wraps.
- *After adding D@30 — only the arc (10,30] moves:* key 12 → D (30), moved; key 50 → C (75), same; key 88 → A (10), same.

### Hit rate and the working set

A cache earns its place only by absorbing reads the store would otherwise serve. With skewed access, a small fraction of keys serves most traffic, so sizing for that working set (plus headroom) gives a high hit rate cheaply. The numbers — working set, per-shard memory, and the database load a miss creates — are derived in the estimation section.

![Dataset vs working set](diagrams/working-set.svg)

### Replication and failover

Each shard is a **primary plus one or more replicas**. Replication is **asynchronous by default**: the primary acks immediately and the replica catches up in the background, keeping writes at one round-trip. The exposure (a write lost if the primary dies before replicating) is bounded, because the database is the source of truth — a lost cache write becomes a miss that refills on the next read. A membership service detects a dead primary and promotes a replica.

![Replication and failover](diagrams/replication-failover.svg)

### Invalidation

When the underlying data changes, the cache must not keep serving the old value past its contract. The default is **cache-aside with delete-on-write**: the application reads through the cache and, on a write, deletes the key and lets the next reader refill it. Updating the cache in place on a write invites races; deleting is safer. Every entry also carries a TTL as a backstop.

![Cache-aside invalidation](diagrams/invalidation.svg)

### Hot keys and stampedes

Consistent hashing spreads *keys* evenly but not *traffic*. A viral post or a global feature flag concentrates load on one shard (a **hot key**), and a popular key expiring sends every concurrent reader to the database at once (a **stampede**). Both are mitigated on the read path; the hot-key deep dive covers how.

![Hot keys: replicate across shards](diagrams/hot-keys.svg)

> **Key idea.** A distributed cache is a counter-free hash table made fault-tolerant: shard it, route to it, replicate it, and decide how writes invalidate it.

## 1. Requirements

> **Before reading on.** List the requirements, then name the property you would never compromise and the constraint that drives the design. They are different here.

### 1.1 Functional requirements

- **`GET(key)`** returns the cached value or a miss signal.
- **`SET(key, value, ttl)`** writes a value with a time-to-live.
- **`DELETE(key)`** removes a key (used to invalidate on writes).
- **Shard** across many nodes so capacity and throughput scale horizontally.
- **Replicate** each key so a single node loss does not permanently drop every key it held.

### 1.2 Non-functional requirements

- **Latency** — p99 sub-millisecond for hits within a region. This is the point of the system.
- **Availability** — the cluster survives single-node failure transparently.
- **Throughput** — on the order of 1M ops/sec at the baseline, engineered to hold at 10×.
- **Bounded blast radius** — a node loss must not send more than `~1/N` of cache traffic through to the database.
- **Bounded staleness** — stale reads are allowed but bounded by an explicit TTL or invalidation contract, never unbounded.

### 1.3 The constraint versus the property

**Correctness within the contract is the property to protect**: a cache may be stale *within* its TTL or invalidation contract, but it must never serve a value that contradicts the contract. **Hit rate is the constraint that drives the design** — because the cache exists only to keep traffic off the store, and a few points of hit rate decide whether the database survives a shard loss. Everything downstream — working-set sizing, replication, invalidation — serves that.

> **Key idea.** Protect correctness-within-contract; design around hit rate, because hit rate decides how much load reaches the store.

## 2. Back-of-the-envelope estimation

The numbers size three things: the working set (so the cache is cheap), per-shard memory, and the database load a miss creates — especially the **cold-start** load when a shard dies. The figures are illustrative anchors.

**Interactive calculator widget (default values shown):**

| Input | Default |
|---|---|
| Cluster ops / sec | 1.0M |
| Total keys | 1.0B |
| Avg value size | 1.0 KB |
| Working set (% of keys) | 10% |
| Hit rate | 99.0% |
| Shards | 100 |

**Computed outputs:**

| Output | Value | Note |
|---|---|---|
| Working set | 100 GB | of 1.0 TB dataset |
| Per-shard memory | ~1.3 GB | working set ÷ 100 + 30% |
| DB load (steady) | 10K/s | 1.0% miss × 1.0M |
| DB load (1 shard dies) | 20K/s | 2.0× steady |

Formula: `cold-start DB = 10K/s steady + 10K/s (dead shard at 0% hit) = 20K/s`. Size for the working set, not the dataset. And size the DB for the cold case — when a shard dies its keys fall through to the DB at full rate, not the steady miss rate.

### 2.1 Working set, not dataset

Assume 1 billion keys at ~1 KB each — a 1 TB dataset. Under skewed access where ~10% of keys serve ~90% of traffic, the working set at a 90%+ hit rate is closer to **100 GB**. Spread across 100 shards that is **~1 GB per shard**, and ~40% headroom for entry overhead, eviction structures, and replication buffers makes each shard want **~1.4 GB resident**. Size for the working set, not the dataset.

### 2.2 Per-shard throughput

At 1M cluster ops/sec across 100 shards, the average shard sees **~10K ops/sec**, well within one node. But traffic skew means the hottest shard can see 3–5× the average, so each shard must comfortably handle **~50K ops/sec** or the hottest shard becomes a hot spot (the hot-key deep dive).

### 2.3 Database load, steady versus cold

The cache's purpose is to keep load off the store. At a 99% hit rate and 1M ops/sec, steady-state database load is `1% × 1M = 10K QPS`. The number that actually sizes the database is the **cold case**: when one of 100 shards dies, its keys (another ~10K QPS) fall through at a 0% hit rate until a replica warms — so the database briefly sees **~20K QPS**, double steady-state. Size for the cold case, not the average.

> **Key idea.** The binding number is cold-start database load — a dead shard's keys hit the store at full rate, several times the steady miss rate.

## 3. API design

**Design checkpoint:** *A caller needs to increment a shared counter held in the cache from many app servers at once. Should they do GET then SET, or is a single operation needed?* Options: (a) *GET the value, add one, SET it back*; (b) *A single atomic INCR the cache executes server-side* — confirmed as (b) by the following section's mention of `INCR` as a server-side atomic primitive.

The core is three operations; the rest are what make it usable at scale.

### 3.1 Core operations

`GET(key)`, `SET(key, value, ttl)`, `DELETE(key)` *(Request & response detail expandable in the live UI for each, not expanded here.)*

### 3.2 The operations that define a distributed cache

`GETS`/`CAS` give a versioned read and a conditional write (the basis for safe fills and leases); `INCR` and `ADD` are atomic primitives; `MGET` batches reads so a caller assembling many keys pays one round trip instead of one per key — at a million QPS that is the difference between feasible and not.

`GETS(key)`, `CAS(key, value, version, ttl)`, `MGET([k1, k2, ...])`

> **Key idea.** Atomic primitives (`INCR`, `ADD`, `CAS`) and batched `MGET` are server-side concerns; an overloaded shard should reject fast (`BUSY`), not queue.

## 4. Data model

### 4.1 The cache entry

The cache stores opaque bytes keyed by an opaque string — it never parses the value. Each entry carries an absolute expiry, a version for `CAS`, and its size for eviction accounting. Fields: `key` (string), `value` (bytes), `expires_at` (timestamp), `version` (int), `size` (int).

The cache deliberately does **not** hold a schema (the caller versions it, e.g. `user:v3:12345`) or any secondary index — range scans belong in a database. Each shard keeps two bookkeeping structures: an **LRU list** (a doubly-linked list ordering entries for eviction) and a **TTL index** (a min-heap by `expires_at`) so expiry can be lazy or sampled without scanning every entry.

### 4.2 Where each key lives

The key decides the shard, so key *shape* is a design choice. Co-locate related keys with a hash tag — `user:{12345}:profile` and `user:{12345}:settings` hash only the braced part, so both land on one shard for batch fetches. Watch cardinality (`session:<uuid>` is unbounded; `country:<iso>` is ~200 values) and hot keys (`feature_flag:global` pins to one shard, the first place to plan hot-key mitigation).

> **Key idea.** The value is opaque bytes; the key's shape decides its shard, its cardinality budget, and whether it becomes a hot spot.

## 5. High-level design

> **Reading the diagrams.** Each step marks the components newly added at that step with a dashed outline and a **NEW** badge.

### 5.1 A single cache box

One in-memory cache in front of the database: the app checks it, and on a miss reads the database and populates the cache.

![Step 1: a single cache box](diagrams/step1-single-cache-box.svg)

It breaks three ways: the working set outgrows one node's memory; with many nodes, clients can't tell which node owns a key; and if the node dies, every key it held vanishes and the database absorbs the full miss traffic.

### 5.2 Fix 1: shard the keyspace

Split the working set across N nodes on a consistent hash ring. Each shard owns an arc of the ring; adding or losing a node remaps only `~1/N` of keys. Capacity and throughput scale linearly.

![Step 2: shard the keyspace](diagrams/step2-shard-keyspace.svg)

Now capacity scales, but nothing yet tracks which nodes are alive and own which arc, or routes a client to the right one.

### 5.3 Fix 2: a routing layer and a membership service

This is where the membership service from Key concepts is added to the architecture: an authoritative component that tracks which nodes are alive and own which arc, sitting behind a **routing layer** that reads membership to direct each request. Membership state itself propagates either via **gossip** (nodes periodically exchange state with a few random peers until the view converges cluster-wide) or through a separate coordination service like ZooKeeper or etcd.

![Step 3: routing layer and membership service](diagrams/step3-routing-membership.svg)

A shard is still a single point of failure for its keys: if it dies, its slice of traffic falls straight through to the database.

### 5.4 Fix 3: replicate each shard

This applies the replication-and-failover mechanism from Key concepts to close that gap: give each shard a **primary and a replica**, with the membership service already in the architecture promoting the replica on failure. Replication stays **asynchronous by default** — the primary acks immediately and the replica catches up — keeping writes at one round-trip. Synchronous replication doubles write latency and pays off only when the cache is itself the system of record or a miss is too expensive to tolerate.

![Step 4: replicate each shard](diagrams/step4-replicate-shard.svg)

### 5.5 The composed read path

The application owns the fill on a miss — the cache server never talks to the database, which keeps the cache simple and the consistency contract with the caller.

**Sequence diagram widget** (with alt block for hit/miss): App → Routing layer: `GET user:12345` (1) → Shard primary: `forward to owning shard` (2) → `value` (3) *[hit branch]*; alternatively Shard primary → App: `MISS` (4) *[miss branch]* → App → Database: `read row` (5) → App → Routing layer: `SET user:12345 value ttl=300` (6) → Shard primary: `write (async-replicates)` (7).

> **Key idea.** Each component answers one failure: shard for capacity, routing + membership so a request finds its key, a replica so a node death is survivable — and the app, not the cache, fills on a miss.

## 6. Deep dives

### 6.1 The routing topology

> **Before reading on.** The cache must find a key's shard on every request. Where should the hashing live — in the client, a proxy, or the cluster itself?

Three placements trade latency against operational cost.

**Topology comparison diagram:**
- *Client-side hashing:* Client + hash → Owning shard.
- *Proxy routing:* Client → Proxy → Owning shard.
- *Server-coordinated:* Client → Any shard → *(redirect)* → Owner.

Three topologies trade off where routing logic lives:

- **Client-side hashing** embeds the ring in each client — one round trip, the lowest latency, no proxy hop — but every client implements the protocol, and every membership change must propagate to all of them.
- **Proxy routing** puts a stateless proxy fleet between clients and shards: simple clients, with protocol and topology changes isolated to one tier, at the cost of one extra hop (microseconds) and a fleet to scale.
- **Server-coordinated** clusters have nodes redirect misrouted requests (Redis Cluster's `MOVED`): lightweight clients, but protocol complexity and redirect latency live in the server.

The ring needs **virtual nodes** to keep load even, and the reason is in the arcs. With a single ring point per physical node, the gaps between nodes fall wherever the hash lands — uneven — so one node can end up owning a much larger arc, and far more keys, than its neighbors. Give each physical node many points instead — 100–200 scattered around the ring — and the arcs average out, so every physical node owns close to `1/N` of the keys. The same spread bounds failure: losing one of 100 physical nodes scatters its ~1% of keys across many neighbors rather than dumping a whole arc on the single next node.

**What separates answers — routing:**
- **Bad** — Hand-waves routing as 'the client hashes'
- **Good** — Names a routing layer and the membership service
- **Great** — Justifies the topology and bounds the failure

### 6.2 Invalidation correctness

> **Before reading on.** A reader misses and is about to write the value it fetched. A writer updates the row and clears the cache in between. What does the cache hold afterward?

This is the **cache-aside race**, and it produces a stale entry that survives until the TTL fires.

**Sequence diagram widget:** Reader A → Database: `miss, read v1` (1) *("stalls before filling cache")*; Writer B → Database: `update to v2` (2); Writer B → Cache: `DELETE key` (3); Reader A → Cache: `SET key = v1` (4) → *("cache holds v1, database holds v2")*.

There is a menu of defenses, picked by workload. **Versioned keys** (`user:12345:v37`, bumped on write) send stale fills to orphaned keys nobody reads again — good for read-mostly data with a cheap version column. **CAS / lease tokens** hand the reader a token at miss time that a concurrent write invalidates, so a stale fill is rejected — good for hot read-modify-write keys. An **invalidation queue** (the writer publishes to a durable log; cache nodes subscribe and delete) is the right shape when many things must be invalidated — per-region caches, local caches, search indexes — at the cost of sub-second queue lag. For everything else, cache-aside plus delete-on-write plus TTL is the default.

The lease token is the subtle case. Reader A misses `user:12`, and the cache hands back a **lease** `L1` — a generation stamp for that key — along with the miss. A's job is to fetch from the database and fill with a conditional write, `SET_IF_LEASE(user:12, v1, L1)`. Meanwhile writer B updates the row to v2 and invalidates `user:12`, which **bumps the key's lease generation to `L2`** and drops any value. When A finally runs its conditional fill with `L1`, the cache compares `L1` to the current `L2`, sees they differ, and rejects the write. The stale v1 never lands; the next reader misses and fills v2. The token turns "last writer wins" into "only a fill holding the current lease wins."

**Sequence diagram widget (lease token):** Reader A → Cache: `GET user:12 (miss, take lease L1)` (1); Cache → Database: `read v1` (2); Writer B → Database: `update to v2` (3); Writer B → Cache: `invalidate user:12 (lease becomes L2)` (4); Reader A → Cache: `SET_IF_LEASE(user:12, v1, L1)` (5) → *("L1 differs from current L2, reject the stale fill")*.

**What separates answers — invalidation:**
- **Bad** — Cache-aside with TTL, never names the race
- **Good** — Names the race, picks one defense
- **Great** — Picks per workload from the menu

### 6.3 Cold start and shard loss

> **Before reading on.** A shard dies. Its keys were served at a 99% hit rate; now they miss entirely until a replica warms. What does the database see, and did you size it for that?

At a 99% hit rate, 1M ops/sec, and 100 shards, steady database load is 10K QPS. Lose one shard and its ~10K QPS of traffic falls through at a 0% hit rate, so the database sees ~20K QPS until the replica warms. If the database was sized for the steady number, it goes down with the shard.

![Cold start when a shard dies](diagrams/cold-start-shard-loss.svg)

The fix is to engineer for the cold case, layered:

- **Hot replicas.** Already taking writes, ideally reads too, so failover is to a warm node.
- **Coalesce requests on the cache server.** A per-shard **singleflight** — the first miss for a key fetches while the rest wait for that one result, not per-process app-side locks — so a cold key triggers one database read, not N.
- **Rate-limit misses at the app** so excess misses degrade gracefully instead of overwhelming the database.
- **Warm a rejoining shard gradually** so the database sees a ramp, not a step.

Eviction is part of this: default **LRU** is fine until a sequential scan (a backup or bulk export) touches every key once and evicts the working set, turning a healthy cache cold with no failure at all. **W-TinyLFU** (Caffeine's default) admits a new entry only if a frequency sketch says it's more popular than what it would evict — scan-resistant and consistently better than LRU on skewed workloads. Concretely: the cache holds A, B, C, hit hundreds of times each, so their estimated frequencies are high. A nightly backup scans the table, reading X, Y, Z once apiece. Plain LRU sees X, Y, Z as the most-recently-used and evicts A, B, C — the hot set is gone. W-TinyLFU instead compares each newcomer's frequency estimate to its would-be victim's: X, Y, Z were seen once, A, B, C hundreds of times, so the scan entries are **denied admission** and the working set stays put.

**What separates answers — cold start:**
- **Bad** — Hot replicas and a DB circuit breaker
- **Good** — Hot replicas + per-shard singleflight + sized DB
- **Great** — Treats cold start as a load-test number

### 6.4 Hot keys and stampedes

> **Before reading on.** A single celebrity key gets a million reads a second, and consistent hashing pins it to one shard. How do you spread load the ring won't?

Consistent hashing balances keys, not traffic, so a viral key overloads its shard. Spread a hot *read* key by **replicating it across shards** under salted names (`hot_key#0 … hot_key#N`); readers pick one at random and per-shard load drops by `~N`. A hot *write* key is a different problem — shard the counter and aggregate on read, or move it to a dedicated counter service. An **in-process local cache** (L1) on the app servers, with a short TTL, absorbs the very hottest keys before they reach the cluster — at the cost of invalidating N copies (route back to the 6.2 menu). A **top-K telemetry** layer detects hot keys at runtime so promotion is automatic, not a guess.

A stampede is the time version: a popular key expires and every concurrent reader misses at once. **Singleflight** on the cache server lets the first miss fill while the rest wait on it. Concretely, a hot key expires and 1,000 readers miss in the same instant. The cache server records one in-flight marker for that key; the first miss goes to the database, and the other 999 attach to it as waiters. When the single fill returns, all 1,000 get that one value. Run the same logic per process and each of N app servers issues its own fill — N database reads, not one — which is why it has to live on the cache server, not in app-side locks. **Jittered TTLs** add a small random offset on `SET` so keys written together don't expire together — the cheapest mitigation, on by default. **Probabilistic early expiration** has each reader refresh a hot key slightly before its TTL with rising probability. With a 300-second TTL, a reader rolls the dice on each hit: well before expiry the chance to refresh is near zero, but as the key nears 300s the probability climbs, so one reader near the end refreshes the key and resets its TTL *before* it expires. The rest keep hitting a warm key, and the synchronized miss never happens. Where jitter spreads *writes*, this spreads the *refresh* of a single hot key across its readers.

![Stampede: singleflight on the cache server](diagrams/stampede-singleflight.svg)

**What separates answers — hot keys:**
- **Bad** — Adds replicas, conflates shard and key
- **Good** — Replicate hot keys, singleflight on miss
- **Great** — Menu per case + detection, hot-write is separate

## 7. Variants

For **10× scale** (10M ops/sec, 1 TB working set), shard count grows toward 1,000 nodes, per-shard memory stays ~1.4 GB, and an **in-process L1 cache becomes mandatory** — at million-QPS fan-out the bottleneck on hot keys shifts from shard CPU to shard network bandwidth, and a high L1 hit rate cuts L2 traffic by an order of magnitude. Eviction quality matters more here, so W-TinyLFU over LRU.

For **multi-region**, run a per-region cache in front of a per-region read replica, and invalidate across regions **asynchronously** via change data capture (CDC) — a stream of the database's committed writes that each region subscribes to and applies as cache deletes. This gives read-your-writes within a user's home region and eventual consistency elsewhere within a seconds-scale window. A single global cache layer is the wrong approach — it adds a cross-region round-trip to every access, often slower than the local database.

For **tighter consistency** (a cache in front of inventory or billing), switch to **write-through** so the cache is updated before the write returns, use **lease tokens** (`CAS`) to block stale fills, and exclude strictly-consistent reads from the cache entirely. The **write patterns** trade off the same way: write-through (strong, double write latency), write-back (fast, durability risk if the cache dies before flushing), write-around (DB updated, cache bypassed, next reader refills — avoids polluting the cache on write-heavy keys).

> **Key idea.** The architecture holds at 10× and across regions; only a strict-consistency contract changes the write path to write-through with leases.

## 8. The transferable pattern

The thread through every decision is that **invalidation is a write-path problem, not a cache problem**. The cache data structure itself is simple — a hash table with a TTL. The hard parts — the cache-aside race, the stale-fill window, cross-region lag, the local-cache broadcast, write-through versus write-back — all live on the write side. Whoever owns the write owns the consistency contract.

The same shape recurs anywhere a fast derived view sits in front of a source of truth: materialized feeds, search indexes, denormalized read models, CDN edges. The hot view is sized for its working set, the write path defines how it is invalidated, and failure handling covers the loss of part of it.

## Review: the 30-second answer

- **Stateless clients over a sharded in-memory store**, consistent-hashed across N nodes, each key replicated to a couple of nodes for failover.
- **Cache-aside reads, delete-on-write.** Writes delete the entry rather than updating it (updates race) — the next reader refills.
- **TTL on every entry, with jitter** to avoid synchronized-expiry stampedes; explicit invalidation when read-your-writes is required.
- **Size for the working set, not the dataset.** Under skewed access a small slice of keys serves most traffic.
- **Engineer for three failures:** the cache-aside race (versioning / CAS), the stampede (singleflight / early expiry), and cold start when a shard dies (the database must absorb up to `1/N` of cache QPS).

## Quiz

**Quiz widget** ("Distributed Cache Design Quiz", "Hide All" / "Reveal All" toggle) — 6 questions, each with a "Show/Hide Answer" button. Full text of every question and its revealed answer:

**1) Why size the cache for the working set rather than the full dataset?**
Access is skewed: a small fraction of keys serves most of the reads. Caching that hot fraction yields a high hit rate at a fraction of the dataset's memory, so the cache stays cheap while still keeping most traffic off the database. Sizing for the whole dataset wastes memory on keys that are rarely read.

**2) What is the cache-aside race, and how do you defend against it?**
A reader misses and fetches v1 from the database but stalls; a writer updates to v2 and deletes the key; the reader then fills the cache with the now-stale v1, which persists until the TTL fires. Defenses include versioned keys (stale fills land on orphaned keys), CAS/lease tokens (a concurrent write invalidates the reader's token), or an invalidation queue — chosen by workload, with cache-aside plus TTL as the default.

**3) Why does a dead shard threaten the database, and how do you size for it?**
At a high hit rate the database sees only a small fraction of cache QPS. When a shard dies, its keys miss at a 0% hit rate until a replica warms, so that shard's full traffic — about 1/N of the cluster — falls through to the database on top of steady misses. Size the database for this cold-start spike (with headroom), keep hot replicas, and coalesce misses so a cold key triggers one database read, not many.

**4) Why does replicating a hot key's shard not help, while replicating the key does?**
Consistent hashing pins a key to one shard, so adding replicas of that shard still routes every read of the key to one primary. Replicating the key itself under salted names (hot_key#0 … hot_key#N) lets readers pick a copy at random, spreading the load across N shards. This works for hot read keys; a hot write key needs a sharded counter or a dedicated counter service instead.

**5) Why is asynchronous replication the default for a cache, unlike for a database?**
The database is the source of truth, so a cache write lost when a primary fails before replicating becomes a miss that refills from the database on the next read — no data is lost. Asynchronous replication keeps writes at one round-trip; synchronous replication doubles write latency and only pays off when the cache is itself the system of record or a miss is too expensive to tolerate.

**6) Why prefer delete-on-write over updating the cache in place?**
Updating the cache on every write opens more races (two writers, or a writer and a slow reader, interleaving) and couples the write path to cache layout. Deleting the key and letting the next reader refill from the source of truth is simpler and collapses the stale window to the next read. A TTL backs it up so any missed invalidation self-heals.

## Sources and further reading

- [Scaling Memcache at Facebook — Nishtala et al. (NSDI 2013)](https://www.usenix.org/system/files/conference/nsdi13/nsdi13-final170_update.pdf) — the canonical look-aside cache at scale: leases for stale-set/thundering-herd, regional pools, and cross-region invalidation.
- [Optimal Probabilistic Cache Stampede Prevention — Vattani, Chierichetti & Lowenstein (VLDB 2015)](https://cseweb.ucsd.edu/~avattani/papers/cache_stampede.pdf) — the probabilistic early-expiration rule for spreading refresh off the TTL boundary.
- [TinyLFU: A Highly Efficient Cache Admission Policy — Einziger, Friedman & Manes](https://arxiv.org/pdf/1512.00727) — the frequency-sketch admission filter behind W-TinyLFU's scan resistance.
- [Caching patterns (read-through / write-through / write-behind / refresh-ahead) — Oracle Coherence docs](https://docs.oracle.com/cd/E16459_01/coh.350/e14510/readthrough.htm) — precise definitions of the write-path strategies.
- [Consistent hashing and libketama — metabrew](https://www.metabrew.com/article/libketama-consistent-hashing-algo-memcached-clients) — virtual nodes and the ring used by Memcached clients.

---

