# Design a Pub-Sub / Message Queue (Distributed Message Queue)

Source: https://systemdesignschool.io/problems/message-queue/solution

> Note on fidelity: this page is built from many JS-interactive widgets (sliders, step-through diagrams, tabbed panels, animated simulations, an expandable quiz, and expandable BAD/GOOD/GREAT rating rows) rather than static images. Every widget's full content — including states behind clicks/toggles, and the labels/boxes/arrows inside each diagram — has been clicked through and transcribed below as text, in the same order it appears on the site. The site has no downloadable diagram image files (they're rendered live by JS/SVG, not `<img>` files), so there are no image assets to save for this page.

Tags: **Hard** · Async processing · Append-only log · Ordering · Replication · Consumer groups

---

## Problem statement

Design a durable, high-throughput message system that decouples producers from consumers: producers append events; many independent consumer groups read them at their own pace, replay history, and survive each other's failures.

This is the Kafka lineage — a partitioned, append-only log, not a transient queue. In scope: publishing to a topic, consuming as part of a group while committing progress, replaying from any offset, and retaining messages for a window or compacting the log (keeping only the latest record per key, dropping older ones).

## Clarifying questions

Each answer changes the design, so state it and the assumption it fixes.

- **Delivery guarantee?** At-least-once (duplicates OK, the common default), at-most-once (loss OK, never duplicate), or exactly-once (hard, expensive)? The central design axis.
- **Ordering requirement?** Global order (forces a single partition — no parallelism), per-key order (the usual answer — partition by key), or none?
- **Throughput and message size?** Millions of small events/sec versus thousands of large payloads change partition count and storage.
- **Retention?** Replay-the-last-N-days (log retention), consume-and-delete (queue semantics), or compacted (latest per key)?
- **How many consumer groups, and are they independent?** Fan-out to many independent readers off one stream is the pub-sub strength.
- **Latency versus throughput?** Batching trades latency for throughput; real-time alerting and bulk ETL want different tuning.

## What makes this problem distinctive

A transient queue deletes a message once it's consumed, which is fine for one worker draining tasks — but it cannot replay history, cannot fan out to several independent readers, and loses everything a slow consumer hasn't read yet. The distinctive move here is to keep the data as an append-only log and move the cursor from the server to the client.

Once the data is a durable log and each reader tracks its own position into it, four properties follow that a delete-on-consume queue cannot offer: replay (a reader can move its cursor back), independent fan-out (several readers each keep their own cursor), backpressure (a slow reader lags behind instead of overwhelming the server), and crash recovery (a restarted reader resumes from its last saved position). Everything later in the design — how the log is split for parallelism, how it is replicated for durability, and when a reader saves its position — builds on that one move.

**Log + per-reader cursor**

![Log plus per-reader cursor](diagrams/log-per-reader-cursor.svg)

**Key idea.** A pub-sub system is an append-only log with the cursor moved from the server to the client; that one move buys replay, fan-out, backpressure, and crash recovery.

## Key concepts

This section covers the concepts needed to solve this problem — prerequisites for the design work that follows.

### The partitioned append-only log

A topic is a named stream split into N partitions, each an ordered, append-only log of messages. Ordering is guaranteed within a partition, not across them — which lets the design provide both order (per partition) and parallelism (many partitions).

![The partitioned append-only log](diagrams/partitioned-topic.svg)

### Offsets and consumer groups

Each message has an offset — a monotonic position within a partition. A consumer group divides the partitions among its members (one partition per consumer), and each group tracks its own offset per partition, so the broker stores no per-reader state and many groups read independently.

![Offsets and consumer groups](diagrams/offsets-consumer-groups.svg)

### Producers and pull-based consumers

A producer picks a partition — by key hash for ordering, or round-robin for spread. A consumer pulls batches and tracks its own offset, so it reads at its own rate, replays from any offset, and a slow consumer lags rather than overwhelming the broker — natural backpressure.

![Producers and pull-based consumers](diagrams/producers-pull-consumers.svg)

### Delivery semantics

The guarantee depends on when you commit the offset relative to processing. Commit before processing risks loss (at-most-once); commit after risks reprocessing on a crash (at-least-once — the practical default); exactly-once needs the output and the offset commit to be atomic.

![Delivery semantics: three contracts](diagrams/delivery-semantics.svg)

### Replication, ISR, and acks

Each partition has a leader (handles reads/writes) and follower replicas; caught-up followers form the in-sync replica set (ISR). With acks=all, a write commits only once the ISR has it — the durability-versus-latency dial.

![Replication, ISR, and acks](diagrams/replication-isr-acks.svg)

**Key idea.** Partitions give parallelism and per-key order; the per-reader offset gives replay and fan-out; replication plus ISR plus acks give durability; when you commit the offset gives the delivery semantic.

## 1. Requirements

*Before reading on: List the requirements, then name the property you would never compromise and the constraint that drives the design.*

### 1.1 Functional requirements

- Publish a message to a topic.
- Subscribe/consume from a topic as part of a consumer group; commit progress (offset).
- Replay from an arbitrary offset (re-process history).
- Retain messages for a configured window (time/size), or compact to the latest value per key.

### 1.2 Non-functional requirements

- **Throughput** — order of millions of messages/sec; scale linearly by adding partitions and brokers.
- **Durability** — a committed message survives f broker failures (set by replication and acks).
- **Ordering** — guaranteed within a partition.
- **Availability** — producers and consumers keep working through broker failure (leader failover).
- **Delivery guarantee** — at-least-once by default, with a path to exactly-once for callers that need it.

### 1.3 The constraint versus the property

Durability of a committed message is the property to protect: once acked under acks=all, the message survives broker loss — which is why the ISR and replication exist. Throughput is the constraint that drives the design: millions of messages per second forces the partitioned log, sequential disk appends, and pull-based consumers, and makes partition count the unit of both parallelism and placement.

**Key idea.** Protect committed-message durability via replication; design around throughput, which forces partitioning and the append-only log.

## 2. Back-of-the-envelope estimation

The numbers size the partition count (from throughput) and storage (from retention × replication) — and partition count is also the ceiling on consumer parallelism. Illustrative anchors.

**Interactive estimation widget (default values shown):**

| Input | Default |
|---|---|
| Messages / sec | 1.0M |
| Avg message size | 1.0 KB |
| Per-partition throughput | 50 MB/s |
| Retention | 7d |
| Replication factor | 3× |

**Computed outputs:**

| Output | Value | Basis |
|---|---|---|
| Ingest | 1.0 GB/s | 1.0M × 1KB |
| Min partitions (throughput) | 20 | also the parallelism ceiling |
| Stored | 1.8 PB | 7d × 3 replicas |
| Retention dial | 7 days | the storage knob |

Formula shown: `partitions ≥ 1.0 GB/s ÷ 50 MB/s = 20 (size higher for consumer parallelism)`. Caption: "Ingest sets the partition count — which is also the ceiling on a consumer group's parallelism, so size partitions for peak consumer scale, not just producer throughput. Retention × replication sets storage."

### 2.1 Throughput and partitions

At 1M messages/sec × 1 KB = 1 GB/sec. At a conservative ~50 MB/sec per partition, that is ≥ ~20 partitions for raw throughput — but more (100+) are typically provisioned for consumer parallelism and headroom, because a consumer group can have at most one consumer per partition.

### 2.2 Storage and retention

1 GB/sec × 7-day retention = ~600 TB, and × replication factor 3 = ~1.8 PB. Retention is the storage dial: keep more history, pay more disk. Tiered storage (old segments to object storage) decouples retention from broker disk.

**Key idea.** Throughput sets a partition floor, but consumer parallelism sets the real partition count; retention × replication sets storage.

## 3. API design

**Design checkpoint widget:** *"A consumer reads a message, processes it, then crashes before committing its offset. On restart, what should it do — and what does that tell you about exactly-once?"* Options: (a) *The broker remembers it was delivered and skips it on restart*; (b) *The consumer re-reads from its last committed offset and reprocesses it*.

Five operations: publish, pull, commit, replay, and topic creation.

`GET produce(topic, key, value)`
**Request & response (expanded):**
- Response body: `(partition, offset)` // key decides the partition

`GET poll(group, topics)`
**Request & response (expanded):**
- Response body: `[messages...]` // pull, batched

`GET commit(group, partition, offset)`
**Request & response (expanded):**
- Response body: `ok` // record progress; timing sets the semantic

`GET seek(group, partition, offset)`
**Request & response (expanded):**
- Response body: `ok` // replay from anywhere

`GET createTopic(name, partitions, replicationFactor, retention)`
**Request & response (expanded):**
- Response body: `ok` // partitions set parallelism; RF sets durability

Topic creation is a design decision, not an afterthought: the partition count caps consumer parallelism (one partition is read by one consumer in a group) and the replication factor sets how many broker failures a committed message survives — both flow straight from the estimation numbers.

**Key idea.** The key controls partitioning and order; poll is pull (a slow consumer lags, not overwhelms); when you commit sets the delivery guarantee; seek replays; createTopic fixes the parallelism and durability dials up front.

## 4. Data model

The data model begins with a message on a topic; each thing it can't represent adds the next piece.

### 4.1 The log, the offset, the cursor, the segment

- `Message`: `string key`, `bytes value`, `timestamp ts`, `long offset`
- `Partition`: `int partition_id`, `Segment[] segments`
- `GroupOffset`: `string group`, `int partition_id`, `long next_offset`

A single stream can't give both ordering and parallelism, so a topic is N partitions, each an ordered append-only log. "Ordered" needs a name for a position, so each message has an offset (monotonic, per-partition). The broker can't track who has read what for every reader, so the cursor moves to the consumer — a group offset per partition. And retention can't depend on consumption, so a partition persists as segment files on disk, aged out by time/size or compacted, independent of who has read it.

Each partition's segments live on a broker's disk, and a topic's partitions spread across brokers — so partition count is both the parallelism ceiling and the unit of placement. Offsets live separately from the data, since they belong to readers. The log is immutable and shared; only the cursors move.

**Key idea.** Immutable partitioned log on disk as segments; per-reader cursors; the log never moves, only the offsets do.

## 5. High-level design

The design is derived incrementally, each problem pulling in the next piece.

*Reading the diagrams: each step marks the components newly added at that step with a dashed outline and a NEW badge.*

### 5.1 Durability via an append-only log

Messages must survive a broker restart, so write each to an append-only log file on disk. Immutability is what later enables replay and multiple readers.

![Step 1: durability via an append-only log](diagrams/step1-append-only-log.svg)

### 5.2 Fix 1: partitions for parallelism

One log on one broker can't absorb millions/sec, and one consumer can't drain them. Split the topic into N partitions, each its own append-only log across brokers; producers hash keys for ordering or round-robin for spread.

![Step 2: partitions for parallelism](diagrams/step2-partitions.svg)

### 5.3 Fix 2: consumer independence via offsets

A crashed consumer must resume without losing its place or re-reading everything. Move the cursor from broker to reader: each consumer group keeps its own offset per partition, so crash recovery, replay, and independent fan-out come together.

![Step 3: consumer independence via offsets](diagrams/step3-consumer-offsets.svg)

### 5.4 Fix 3: fault tolerance via replication

A single-broker partition is vulnerable. Each partition gets a leader (all reads/writes) plus follower replicas on other brokers; caught-up followers form the ISR, and acks=all commits only after the ISR has the write. A coordinator tracks leadership, replica health, and group membership.

![Step 4: fault tolerance via replication](diagrams/step4-replication-fault-tolerance.svg)

**Key idea.** Each piece answers one need: an append-only log for durability, partitions for parallelism, per-reader offsets for independence and replay, leader/ISR replication for fault tolerance.

## 6. Deep dives

### 6.1 Partitioning and ordering

*Before reading on: A customer needs every event for a given user processed in order, but you also need 100-way parallelism. Can you have both — and what's the lever?*

Yes, by per-key order: hash the key to a partition, so all of one user's events land in one partition (ordered) while 100 partitions give 100-way parallelism. What you cannot have is global total order plus parallelism — global order means one partition. Two caveats: partition count is hard to change later (raising it rehashes keys and breaks per-key order for in-flight keys, so over-provision up front), and a hot partition from a skewed key (one very high-volume key) is throttled to one partition's throughput — spread it with a compound key or accept the bound.

![Per-key ordering with parallelism](diagrams/per-key-ordering.svg)

**What separates answers — partitioning and ordering (expanded BAD / GOOD / GREAT rows):**
- **BAD — Assumes global ordering without naming the single-partition tradeoff.** Promises global order without noticing it forces a single partition and kills parallelism.
- **GOOD — Per-key order via key→partition; parallelism = partitions.** Hashes keys to partitions for per-key order and scales by partition count.
- **GREAT — Names the global-vs-parallel tradeoff, over-provisions, handles hot partitions.** States that global order and parallelism are mutually exclusive, over-provisions partitions to avoid a rehash, and spreads hot keys.

### 6.2 Delivery semantics

*Before reading on: What does the offset commit have to be atomic with for exactly-once — and why is that hard across systems?*

Three contracts, set by when you commit. At-most-once commits the offset before processing — a crash loses the message; rare, only when loss beats duplication. At-least-once commits after processing — a crash before commit reprocesses, so make consumers idempotent (dedupe by event id). Exactly-once needs the output and the offset commit to be one atomic step; the broker provides it within itself via an idempotent producer (a producer id + per-partition sequence lets the broker drop retries) plus transactions (atomically write outputs and commit offsets), but an external sink still needs its own idempotent write — the broker can't make a third-party side effect transactional.

Consider the within-broker case. A stream processor reads the message at offset 10, computes a result, writes it to the output topic, and commits its new position (offset 11) — and the output write and the offset commit go into one broker transaction that either both land or neither does. Two failure modes show why that matters. If the processor crashes after writing the output but before committing, the transaction never finalizes: its output is marked aborted (downstream readers configured for committed-reads skip it), and on restart the processor re-reads offset 10 and redoes the work — no duplicate escapes. On a producer retry of the same write, the producer id plus per-partition sequence number let the broker recognize the resend and drop it, so a network hiccup doesn't double-append.

**sequence**

![Exactly-once read-process-write transaction](diagrams/exactly-once-sequence.svg)

**three contracts**

![Three delivery contracts](diagrams/three-contracts-deepdive.svg)

**What separates answers — delivery semantics (expanded BAD / GOOD / GREAT rows):**
- **BAD — Exactly-once by committing after processing.** Conflates at-least-once with exactly-once and ignores the crash window.
- **GOOD — At-least-once + idempotent consumers.** Commits after processing and dedupes; names the duplicate case.
- **GREAT — Why EOS is hard; idempotent producer + transactions; external sinks.** Explains the atomic read-process-write requirement, names the idempotent producer + transactions for within-broker EOS, and notes external sinks still need idempotent writes.

### 6.3 Replication, durability, and rebalancing

*Before reading on: A broker holding a partition leader dies, and consumers come and go constantly. What keeps committed data safe, and what is the operational failure mode that matters most?*

In the write path with replication factor 3, the leader is L and the followers are F1, F2. The producer sends a record to L, which appends it at offset 42 to its own log. F1 and F2 continually fetch from L and append the same record. Once every replica in the ISR has offset 42, L marks it committed and acks the producer; with acks=all the producer only sees success after that point, so a committed record exists on all in-sync replicas. Now suppose F2 falls behind (a slow disk): the leader drops it from the ISR and keeps committing with just L and F1, trading one replica of safety for progress. If L then dies, the coordinator elects a new leader only from the ISR — F1, which is known to hold offset 42 — so no committed record is lost. A record that L had appended but not yet committed (the ISR hadn't caught up) can be lost, which is why acks=all waits for the ISR.

The failure mode that matters most in production is consumer-group rebalancing. For example, a topic has 3 partitions P0, P1, P2 and a group of 2 consumers: C1 owns P0, P1 and C2 owns P2. A third consumer C3 joins. The group coordinator triggers a rebalance: every consumer briefly stops processing, the partitions are reassigned to one each (C1→P0, C2→P1, C3→P2), and each consumer resumes its new partition from the last committed offset. The pause is the cost. If consumers flap, or a consumer takes longer than the poll timeout to process a batch, the coordinator assumes it died and rebalances again — repeated rebalances can stall the whole group. Consumer lag (how far each partition's committed offset trails its newest offset) is the signal to monitor: rising lag means scaling out, up to the partition count, or making processing faster.

**Before / after**

![Consumer-group rebalance](diagrams/consumer-rebalance.svg)

**What separates answers — replication and operations (expanded BAD / GOOD / GREAT rows):**
- **BAD — Replicate for durability, no detail.** Says "replicate" without acks, ISR, or failover specifics.
- **GOOD — Leader/follower + ISR + acks=all; names failover.** Uses ISR with acks=all and elects a new leader on failure.
- **GREAT — acks as the dial, ISR-only election, rebalancing + lag.** Ties acks to durability-vs-latency, elects only from the ISR (no loss), and treats rebalancing and consumer lag as the realities that decide whether it works under load.

## 7. Variants

These are follow-up extensions once the baseline log design is accepted, not new baseline requirements.

For **10× scale** (tens of millions/sec), add partitions and brokers; the limits become coordinator metadata load and cross-broker replication bandwidth, and tiered storage (old segments to object storage) decouples retention from broker disk.

For **exactly-once across systems**, the usual answer is at-least-once + idempotency: give every event a stable id and make each sink dedupe on it; reserve broker transactions for the within-broker read-process-write loop.

For **queue semantics**, a work queue (consume-and-delete, competing consumers, no replay) is a mode of this design — a single consumer group with short retention. The log model can emulate queue semantics while still keeping replay and fan-out.

**Log compaction** is a different retention mode. Time/size retention drops old records; compaction instead keeps the latest record per key and discards superseded ones, turning the log into a durable changelog. For example, a log containing (k1=A), (k2=B), (k1=C), (k2=tombstone). After compaction, k1=A is gone (superseded by k1=C), and k2 is dropped entirely because its latest record is a tombstone (a delete marker). What remains is the current value of every live key — useful for event sourcing or change-data-capture, where a consumer that reads the compacted log from the start rebuilds the present state without replaying all history.

**Before / after compaction**

![Log compaction](diagrams/log-compaction.svg)

**Key idea.** The same log scales by partitions and tiered storage; queue semantics and compaction are modes of it, and end-to-end exactly-once is at-least-once plus idempotency.

## 8. The transferable pattern

A pub-sub system is an append-only log with the cursor moved from the server to the client. That one move — consumers track their own offset into an immutable, partitioned, replicated log — provides replay, independent fan-out, natural backpressure, and crash recovery all at once. Partitioning gives parallelism and per-key order; replication and the ISR give durability; when you commit the offset gives the delivery semantic. The same shape recurs wherever producers decouple from consumers over time: event sourcing, change-data-capture, stream processing, write-ahead logs, even a database's own replication log. Framed as "a shared log plus per-reader cursors," the delivery-semantics question reduces to one: what is atomic with the offset commit?

### Review: the 30-second answer

- A topic is a partitioned, append-only log. Each partition is ordered by offset; ordering holds within a partition, not across.
- Producers pick a partition (key hash for order, round-robin for spread); consumer groups divide partitions, so parallelism = partition count.
- Consumers pull and track their own offset — read at their own rate, replay from anywhere, don't interfere with other groups; the broker doesn't push, which is natural backpressure.
- Delivery is a choice: at-most-once, at-least-once (default — expect duplicates), or exactly-once (idempotent producer + transactions, or idempotent consumers).
- Durability via replication: leader + in-sync replicas; acks=all commits only once the ISR has the write.

## Quiz

**Pub-Sub / Message Queue Design Quiz** ("Hide All" / "Reveal All" toggle) — 5 questions, each with a "Show/Hide Answer" button. Full text of every question and its revealed answer:

**1) Why is a topic a partitioned log rather than a single stream?**
A single ordered stream can give ordering or parallelism but not both: one log means one writer and one reader for order's sake. Splitting the topic into N partitions, each an ordered append-only log, keeps ordering within each partition while allowing N producers and N consumers to work in parallel. Ordering is therefore guaranteed per partition, not globally across the topic.

**2) Why move the read cursor from the broker to the consumer?**
If the broker tracked who has read what for every reader, it would carry per-reader state and couldn't let independent groups read at different speeds. Storing the offset on the consumer group instead makes the log immutable and shared: each group tracks its own position, so many groups fan out independently, any group can replay from any offset, and a crashed consumer resumes from its last committed offset.

**3) Why is exactly-once delivery hard, and how is it approximated?**
Exactly-once requires the message's side effect and the offset commit to happen atomically, but they often span systems (process here, write there, commit the offset back), and a crash between them replays one or the other. Within the broker it's provided by an idempotent producer (id + sequence to drop retries) plus transactions; end-to-end, the practical answer is at-least-once plus idempotent sinks that dedupe on a stable event id.

**4) What does acks=all guarantee, and what's the tradeoff?**
With acks=all a write is committed only once every in-sync replica (ISR) has it, so the message survives the loss of the leader and up to the number of remaining in-sync followers. The tradeoff is latency: the producer waits for replication before the write is acknowledged. acks=1 (leader only) is faster but loses un-replicated writes if the leader dies, so acks is the durability-versus-latency dial.

**5) Why can't partition count be freely increased later, and what operational issue appears under load?**
Raising the partition count changes the key→partition mapping, so in-flight keys can move partitions and lose their per-key ordering — which is why partitions are over-provisioned up front. Under load, the key operational issue is consumer-group rebalancing: when consumers join, leave, or crash, partitions reassign and processing pauses, and frequent rebalances (from flapping consumers or slow processing) stall the group. Consumer lag is the metric that signals it.

## Sources and further reading

- *Apache Kafka documentation* — the partitioned append-only log, consumer groups and offsets, and the ISR + acks durability model.
- *Exactly-once semantics in Kafka* — Confluent — why exactly-once is hard and how the idempotent producer plus transactions deliver it within the broker.
- *The Log: what every software engineer should know about real-time data* — Jay Kreps (LinkedIn Engineering) — the append-only log and per-reader cursor as a unifying abstraction.
