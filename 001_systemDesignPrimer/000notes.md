# System Design Primer

Source: https://systemdesignschool.io/primer

> Note on fidelity: this page is built from many JS-interactive widgets (sliders, step-through diagrams, tabbed panels, animated simulations) rather than static images. Every widget's full content — including states behind tabs/toggles, and the labels/boxes/arrows inside each diagram — has been clicked through and transcribed below as text, in the same order it appears on the site. The site has no downloadable diagram image files (they're rendered live by JS/SVG, not `<img>` files), so there are no image assets to save for this page.

---


## Introduction

System design knowledge matters for two reasons. First, companies test it in interviews, especially at senior levels. Second, it separates competent engineers from exceptional ones. Writing code is table stakes. Designing robust, scalable systems requires deeper expertise.

## What Is System Design?

System design is the process of defining the architecture, components, modules, interfaces, and data for a system to satisfy specified **requirements**. It is essentially creating a blueprint for a complex software system to ensure it is efficient, reliable, and scalable.

System design is the art of making technical trade-offs to turn a vague problem into a scalable solution. It is not just about connecting boxes; it is about justifying why you connected them that way based on **constraints**.

### System Design vs Object-Oriented Design

| Aspect | Object-Oriented Design | System Design |
|---|---|---|
| **Example Problems** | Design a parking lot, elevator controller, chess game, vending machine | Design Twitter, YouTube, Uber, Netflix |
| **Scale** | Single machine | Large scale, millions of users, petabytes of data, multiple data centers |
| **Focus** | Code structure, class relationships, design patterns | Architecture, distributed systems, scalability |
| **Skills Tested** | Class design, inheritance, interfaces, SOLID principles | Component selection, data flow, partitioning, replication, trade-offs |
| **Output** | UML diagrams, code implementation, class hierarchies | Architecture diagrams, capacity estimates, API design, database schemas |
| **Execution** | Code runs on single process | Services span multiple servers and regions |

The skills overlap but the emphasis differs. Object-oriented design emphasizes clean code and maintainability. System design emphasizes performance, reliability, and cost at scale.

## What Are System Design Interviews?

Writing code becomes less central as careers progress. Companies need engineers who design systems that handle high traffic, make decisions balancing cost and performance, and lead technical discussions. System design interviews assess these capabilities.

These interviews appear at mid-level and become central to senior positions. At senior levels, engineers contribute to application architecture and make design choices affecting entire systems. Interviews simulate real-world scenarios involving scalability, fault tolerance, and performance.

System design takes years to master. Real systems require thousands of hours of work. Demonstrating competence in 45 minutes presents a unique challenge.

### What System Design Interviews Test

System design interviews test soft skills more than raw knowledge. Can you break down vague, open-ended problems into solvable parts? Do you understand how components interact? Can you design scalable, reliable, maintainable systems?

Every design decision has trade-offs. Explain why you chose one approach over another. Communication matters as much as technical depth. System design rarely follows a straight path — adjust your approach based on new constraints or feedback.

## Level Expectations

Interview expectations change dramatically with seniority. The single biggest difference between levels is the ability to handle ambiguity. Junior engineers receive well-defined problems with clear requirements. Senior engineers face deliberately vague problems requiring clarification and decomposition. Staff engineers navigate extreme ambiguity, defining the problem space itself.

This manifests in problem complexity. L4 problems have simple contracts: given input A, return output B. L5 problems combine 2-3 concepts with some flexibility. L6 problems either combine 4-5 subsystems or present abstract requirements needing significant clarification before design begins.

### Interactive widget: "By level" tab — L4 · Mid

- **Problem scope:** Well-defined problems with guidance
- **Depth expected:** Surface-level understanding
- **Common topics:** Cache, database, load balancer, API design
- **Interview focus:** Template execution, component knowledge, basic trade-offs

L4 problems have simple contracts. TinyURL takes a long URL and returns a short one. A YouTube view counter takes a video ID and returns a count. These are solved problems with standard solutions.

The interview is mechanical: clarify requirements, design APIs, draw the architecture, then a light deep dive. Since the solution is known, interviewers have low tolerance for messy structure. You must own the template.

You need the basic building blocks — load balancer, cache, database, message queue — plus when to use SQL versus NoSQL and vertical versus horizontal scaling. Deep dives stay surface-level: "LRU removes least recently used items" is enough.

Most candidates fail from poor time management, not from lacking knowledge.

**Depth ladder — "strong consistency":** *What is strong consistency?* — concept awareness

### Interactive widget: "By level" tab — L5 · Senior

- **Problem scope:** Clearer requirements, some ambiguity
- **Depth expected:** Moderate depth on 1–2 components
- **Common topics:** Scaling patterns, partitioning, caching strategies, message queues
- **Interview focus:** Architecture choices, trade-offs, common scale challenges

L5 problems scale a specific feature. Top-K songs combines counting and ranking. Flash sales need distributed counters with consistency. News feed sits on the L5/L6 boundary — at L5 you focus on fan-out (push versus pull) and basic storage.

The interview has moderate ambiguity. You must ask about scale and constraints proactively. The mental shift is from "How do I build a component?" to "How do I scale this feature without breaking?" You should identify bottlenecks without prompting.

You need to know how scaling patterns actually work. "Hash-based partitioning on user_id ensures even distribution" — then the trade-off when pushed: "it avoids hotspots, but resizing the cluster is painful."

Deep dives go deeper. Spend real time explaining 1–2 components in detail.

**Depth ladder — "strong consistency":** *How do we implement strong consistency?* — implementation knowledge

### Interactive widget: "By level" tab — L6 · Staff

- **Problem scope:** Ambiguous requirements, must clarify scope
- **Depth expected:** Deep dive on 2–3 components
- **Common topics:** Distributed systems, consistency, replication, consensus, failure handling
- **Interview focus:** Navigating ambiguity, deep knowledge, production reality

L6 problems are either highly ambiguous or infrastructure-level. Design a distributed job scheduler. Design an ad-serving system. These are not features — they are the platform other engineers build on, and they demand strict guarantees: exactly-once, ordered processing, no data loss.

The interview has no template. You lead. Aggressively manage time: "Standard load balancer and SQL for user auth, let's move on." Buy time for the novel problem. Identify what is actually hard: "The hard part isn't storage. It's preventing two workers from grabbing the same job when the network is slow."

You must understand guarantees and failure modes deeply — practical reality, not textbook definitions. "A worker crashed after processing a payment but before sending the success signal. The scheduler retries. Now you charged the user twice. Fencing tokens? Database constraints? Leases?"

The interviewer probes every decision and ties it to business impact: "Why strong consistency over eventual?" and "What if requirements change to prioritize availability?"

**Depth ladder — "strong consistency":** *Why this implementation, and why strong versus eventual here?* — trade-off justification

### Interactive widget: "By company" tab

| Company | Entry | Mid | Senior | Staff+ | Notes |
|---|---|---|---|---|---|
| **Google** | L3 | L4 | L5 | L6–L8 | Slow progression, high bar for senior+. L5 is terminal for most. Expect 3–5 years between levels. |
| **Meta** | E3 | E4 | E5 | E6–E9 | Faster progression than Google. E5 achievable in 2–3 years. Performance-driven culture. |
| **Amazon** | 59–60 | 61–62 | 63–64 | 65+ | Faster initial progression. Strong leadership-principles emphasis. L6+ requires scope beyond code. |
| **Microsoft** | ICT2 | ICT3 | ICT4 | ICT5+ | Slower progression. System design starts at 62+. Emphasis on cross-team collaboration. |
| **Apple** | Entry | Mid | Senior | Staff+ | Secretive leveling. Very slow progression. Product-focused, less emphasis on pure scale. |

Titles at junior and mid-levels are roughly equivalent across companies, but diverge sharply at senior levels. Google favors slow, tenure-heavy progression while Meta rewards rapid impact. Amazon has the "SDE II trap," where reaching Senior needs cross-team scope equivalent to Staff elsewhere. Microsoft climbs steadily until the "Principal cliff" at 65. Apple is design-driven and rigid — you wait to be assigned larger projects rather than creating scope.

## How to Prepare

### The Memorization Trap

The prevailing approach: find popular system design problems, watch YouTube videos showing solutions, read blog posts with architectural diagrams, memorize the answers, repeat.

This works at first. In your interview, you get asked to design Twitter and draw the diagram from memory. But as more interviewers recognize the cookie-cutter answers, the strategy fails.

First, you freeze when facing unfamiliar problems — you memorized 30 solutions, but the interviewer asks about something you haven't seen. Second, interviewers recognize memorized answers; when your design matches the popular YouTube solution exactly, they probe deeper and you can't answer because you memorized the *what*, not the *why*. Third, the approach completely fails at L6+, where interviews are mostly deep dives that expose whether you have actual experience.

### The Better Approach

Learn the fundamentals. Understand how things actually work. Build genuine technical depth.

For L4-L5 engineers with limited time, the hybrid approach works best: master the common patterns and templates, study 10-15 common problems to internalize the template — but for each problem, don't just memorize the solution. Question every design choice. What happens if we remove the cache? Why Cassandra instead of PostgreSQL? Why Redis over Memcached?

### Why System Design School Exists

System Design School takes the fundamentals-first approach. You won't find memorizable Twitter solutions — you'll find building blocks for constructing any solution.

This primer is a quick crash course: essential components, interview templates, and mental models, designed for rapid learning and last-minute preparation. The full course goes deeper, organized into two tracks:

**Fundamentals:**
- **Microservices & Communication** — service-to-service talk, message queues, Kafka, circuit breakers, service discovery, why async communication prevents cascading failures.
- **Scaling Services** — load balancing, auto-scaling, caching patterns, CDNs, cache-aside vs write-through, cache thundering herd.
- **Data Storage** — B-trees, LSM trees, SQL vs NoSQL, document databases vs key-value stores, OLTP vs OLAP.
- **Scaling Data** — replication (primary-replica, multi-leader), partitioning (consistent hashing, range-based), change data capture, partition rebalancing.
- **Batch & Stream Processing** — MapReduce, stream processing, lambda architecture, batch vs stream.
- **Patterns** — rate limiting, unique ID generation, saga pattern, fan-out/fan-in.

**Domain Knowledge:**
- **Transactions** — isolation levels, pessimistic vs optimistic locking, flash sale inventory patterns, preventing double-booking.
- **Distributed Systems** — CAP theorem, PACELC theorem, consistency models, consensus algorithms (Raft, Paxos), failure handling.
- **Geospatial Search** — geohash, quadtrees, H3 hexagonal indexing, S2 library, how Uber finds nearby drivers.
- **Search Engines** — inverted indexes, TF-IDF, BM25, Elasticsearch architecture.
- **Media Systems** — video transcoding, file chunking, adaptive bitrate streaming, how Netflix delivers video.
- **Probabilistic Data Structures** — Bloom filters, count-min sketch, HyperLogLog.

For L4-L5, the primer gets you interview-ready fast. For L6+, you need the full course.

## Where to Start

- Interviewing tomorrow? See [Master Template](#master-template).
- Want core concepts quickly? Review Core Design Challenges and Designing for Scale.
- New to system design? Start with Main Components.
- Familiar with components but need interview structure? Check the step-by-step interview walkthrough.

## How This Primer Is Organized

The rest of this primer builds four capabilities, in order:

- **Foundations** (build a design) — the building blocks and Master Template.
- **Scaling** (handle load) — Core Design Challenges and Designing for Scale.
- **Consistency** (stay correct) — how to stay correct when data is replicated and updated concurrently.
- **Interview Method** (put it together) — the decomposition framework and step-by-step walkthrough.

Interactive widgets throughout let you tweak each idea and watch it respond.

## Core Design Challenges

### Challenge 1: Too Many Concurrent Users

Large user bases introduce many problems. The most common: single machines or databases have RPS/QPS limits.

The solution is repetition — repeat the same assets and assign users randomly to each replication. When replicated assets are server logic, it's **load balancing**. When replicated assets are data, it's **database replicas**.

**single server (before)**

```text
User, User, User → Single server → **RPS limit exceeded**
```
**load balanced (after)**

```text
User, User, User → Load balancer → Server, Server, Server
```

### Challenge 2: Too Much Data to Move Around

Data becomes big when it's no longer possible to hold everything on one machine (Google index, all tweets on Twitter, all Netflix movies).

The solution is **sharding**: partitioning data by logic. If we shard by `user_id` in Twitter, all tweets from one user store on the same machine.

```text
Writes → shard by user_id → Shard A–H | Shard I–P | Shard Q–Z
```

### Challenge 3: The System Should be Fast and Responsive

Response time should be under 500ms; over 1 second creates poor user experiences. Reading is usually fast after replication. Writing is where the challenge lies — most writes involve many queries/updates lasting longer than the 1-second limit.

The solution is **asynchrony**: write requests return immediately after servers receive data and queue it; actual processing continues in the backend. Implemented via message queues like Kafka.

```text
Client → App server → **enqueue then respond** → Queue → Worker → Database *(labelled "write")*
```

### Challenge 4: Inconsistent (outdated) States

Results from solving Challenges 1 and 2: with replication and async updates, reads can see outdated (not wrong, just old) data.

The solution is application-level: build UX where briefly-outdated data is acceptable — **eventual consistency**. Most apps tolerate this well; exceptions are banking/payment apps, which wait for all processing to finish before responding (why they feel slower than Google Search).

```text
Write → Primary → *(replication lag, stale until caught up)* → Replica → Read
```

## Designing for Scale

### Estimating the Load

Put numbers on the problem before choosing a strategy. Derive numbers from usage, not assertion.

**Interactive slider widget (default values shown):**

| Input | Default |
|---|---|
| Daily active users | 10M |
| Write actions / user / day | 20 |
| Reads per write | 100:1 |
| Peak-to-average factor | 3× |
| Stored bytes / write | 2 KB |
| Retention | 3 yr |

**Computed outputs:**

| Output | Value |
|---|---|
| Writes / sec (avg) | 2.3K |
| Reads / sec (avg) | 231.5K |
| Reads / sec (peak) | 694.4K |
| Storage after 3 yr | 448.5 TB |

Formulas shown: `writes/s = 10M × 20 ÷ 86,400 = 2.3K/s` and `reads/s (peak) = 2.3K × 100 × 3 = 694.4K/s`.

Round aggressively — these are order-of-magnitude numbers. Daily actions ÷ 86,400 seconds gives an average rate; multiply by a peak factor for the burst you must survive. Reads usually dwarf writes, so the read path is where caching and replicas go.

### Decomposition

Decomposition breaks requirements into microservices, each focused on a single business capability.

**monolith**

```text
Catalog + Checkout + Payments + Users *("one deploy")*
```
**microservices**

```text
User, Order, Payment *(each "deploy independently")*
```

### Vertical Scaling

Scale up with more powerful machines. AWS EC2 High Memory instances offer up to 24 TB memory; Google Cloud Tau T2D instances optimize for compute-intensive workloads.

```text
Small server (2 vCPU · 8 GB) → *scale up* → Big server (64 vCPU · 512 GB)
```

### Horizontal Scaling

Scale out by running multiple identical stateless instances, distributed via load balancers.

```text
Load balancer → Instance, Instance, Instance, Instance
```

### Partitioning

Splits requests/data into shards distributed across services or databases (by user ID, geography, or other key).

```text
Keys → hash(key) → Shard 0, Shard 1, Shard 2
```

**Consistent hashing widget:** Plain hash-mod-N partitioning has a painful failure mode — changing shard count remaps almost every key. Consistent hashing places nodes and keys on a ring; each key belongs to the next node clockwise. Adding/removing a node moves only the keys in that one arc (~1/N of them). Widget default state: 3 nodes · 12 keys, buttons "+ Add node" / "− Remove node", counter "Keys remapped by the last change: —".

### Caching

Stores frequently accessed data in fast memory (Redis, Memcached) to reduce database load.

```text
App → *(check first)* → Cache → *hit* (return) / *miss* → Database
```

**Hit-rate widget:** Origin load = total load × miss rate, so the last few percent matter most (90%→99% cuts DB traffic another 10×). Default state: Cache hit rate 90%, Total read QPS 50.0K, "▶ Play stream" / "Reset" buttons, "each square is a read, green = hit, red = miss". Results: Reaches the database 5.0K/s, Effective read capacity 10.0×. At 90% hit rate only 5.0K/s reaches the DB instead of 50.0K/s.

### Buffer with Message Queues

High-concurrency write-intensive scenarios can overload systems via disk I/O. Message queues buffer writes, turning sync ops into async ones.

```text
Write service → *(bursty writes)* → Queue → *(steady drain)* → Worker → Database
```

**Direct-vs-queue widget:** Toggle "Direct to server" / "Buffer with a queue". Default demo: Incoming traffic 220 req/s, Server capacity 100 req/s. In "Direct to server" mode: Producer 220 req/s → Server **OVERWHELMED**, Processed 0, Dropped 0, Dropping 120/s — "traffic exceeds capacity, so the single server drops 120 req/s." Switching to "Buffer with a queue" mode routes the same burst into the queue, draining at the server's steady rate — zero drops until a sustained overload eventually fills the buffer.

### Separating Read and Write

Social platforms are read-heavy; IoT systems are write-heavy — read/write separation treats them differently.

**replication**

```text
Writes → Leader → Follower, Follower → Reads
```

**CQRS**: command side (writes: create/update/delete, write-optimized models) and query side (reads, denormalized read-optimized models), with async propagation from command → query side.

**CQRS**

```text
Commands → Write model → Write store → *(sync)* → Read store → Read model → Queries
```

Example: MySQL as source of truth, Elasticsearch for full-text/analytical queries, synced via MySQL binlog CDC.

### Combining Techniques

Effective scaling combines: decomposition (independent scaling) → partitioning + caching (distribute load, boost performance) → read/write separation (leader-replica) → business-logic adjustments.

**combined architecture**

```text
Client → Load balancer → Service A, Service B → Cache *(read)* / Queue → Worker *(write)* → Primary·shard 1, Primary·shard 2 → Replica *(read)*
```

### Adapting to Changing Business Requirements

Not strictly technical, but valuable in interviews. Example: weekly sales events — stagger categories by day/region (baby products Day 1, electronics Day 2) for predictable traffic and pre-loadable caches. Example: eBay auctions — show a temporary "bid success" message on the frontend while backend resolves consistency asynchronously; users see the correct final status after the auction ends.

## Consistency and Tradeoffs

Replication and async updates buy scale but let readers see stale/conflicting data (Challenge 4). This section is about deliberately choosing how correct the system must be. Consistency questions dominate L6 interviews.

### The CAP Theorem

A distributed store can hold only two of three: **Consistency** (every read sees the latest write), **Availability** (every request gets a response), **Partition tolerance** (keeps working when network drops messages between nodes).

**Venn-diagram widget** (three overlapping circles: Consistency, Availability, Partition tolerance; toggle buttons "Highlight CA" / "Highlight CP" / "Highlight AP"):

- **CP** — HBase · MongoDB · Redis — stays consistent, refuses on the cut-off side
- **AP** — Cassandra · DynamoDB · Riak — stays available, may serve stale data
- **CA** — single-node SQL — only possible without partitions

The center (all three) is unachievable. Across machines, partitions happen — P is a given, so the real choice is CP vs AP.

**Leader/replica partition-simulation widget:** buttons "▶ Play writes", "Simulate partition", "Reset"; slider "Replica lag: 2 versions"; toggle "AP · read replica, allow stale" / "CP · require leader". Default state: Leader (source of truth) v0, Replica v0, "A read now returns v0 — fresh." Turning on the partition: AP keeps answering with stale data; CP stops answering.

Most consumer features pick AP (a slightly stale feed beats an error); money picks CP (a wrong balance is worse than a spinner).

### Consistency Models

Consistency is a spectrum, not a switch. **Strong** = every read sees the latest write. **Eventual** = replicas converge over time, reads may lag. **Read-your-writes** = you always see your own updates even if others see them late.

**Widget (tabs: Strong / Read-your-writes / Eventual):** buttons "▶ Play"/"Reset"; scenario "author writes v2 at t=0 · replica lag 3 ticks". Default (Strong) tab result: "replica synced", You (the author) reads **v2** (fresh), Another user reads **v2** (fresh). Description for Strong: "Every read sees the latest write. Reads go to the leader or wait for replicas, so no one ever sees stale data."

Pick the weakest model the feature tolerates — stronger consistency costs latency and availability.

### Contention and Concurrency

Naive read-modify-write on the same record loses updates — both clients read the old value and overwrite each other.

**Widget (tabs: "Naive read-modify-write" / "Optimistic lock (version)"):** step controls "◀ ▶ Play"; scenario "two clients both increment the same counter"; Stored count starts at 10; step 1: `T1 read count → 10`.

**Optimistic locking**: adds a version column, writes with `UPDATE … WHERE version = N`; if another writer already bumped the version, the update matches zero rows and the loser re-reads and retries. Assumes conflicts are rare, pays only when they happen. **Pessimistic locking**: takes a lock up front, trading throughput for certainty.

### Multi-Step Workflows and Sagas

A checkout spans several services (reserve inventory, charge payment, create shipment) with no single cross-service transaction. A **saga** runs steps in order and, on failure, runs compensating actions to undo earlier steps.

**Widget:** dropdown "Fail at: Reserve inventory / Charge payment / Create shipment / None"; step sequence "Reserve inventory (waiting) → Charge payment (waiting) → Create shipment (waiting)"; controls "◀ ▶ Play"; step counter "1 / 5"; status "Saga starts. Each step runs in order."

Compensation is not a rollback — it's a new action reversing a completed one (refund a charge, release a hold). Each step and compensation must be idempotent, since retries repeat them.

## Master Template

High-level takeaway: **write to message queue and have consumers/workers update database and cache; read from cache.**

**Step-through widget** (controls: "◀ Back", "Next ▶", "▶ Play"; "Step 1 / 8"):

- **Step 1 — "One server, one database":** Client → App server (Service) → Database (Storage). "Start with the smallest thing that works: a client calls one app server, which reads and writes one database." *(Reveal one box at a time — in an interview, add each component only when a concrete problem forces it, never all at once.)*
- **Final composed core (step 8):** Client → Load balancer → App server, App server *(read)* → Cache → *(backed by)* Primary DB / Read replica; slow writes → Message queue → Worker.

### Component Breakdown

- **Stateless Services** — scalable, expanded by adding machines behind load balancers. Write Service receives client requests and forwards to message queues. Read Service handles reads by accessing caches.
- **Databases** — cold storage / source of truth; not read directly at high volume.
- **Message Queues** — buffer between writer services and storage. Producers (write services) send changes to queues; consumers update databases and caches (Database Updater, Cache Updater = async workers).
- **Caches** — fast, efficient reads.

### Dataflow Path

**Write path diagram:** Client → Write service → *(write request)* → Queue → *(enqueue, 202 accepted)* → Worker → *(deliver)* → Database → *(persist)*, Cache → *(update)*. "The write path accepts the request, enqueues it, and returns immediately; a worker persists to the database and updates the cache asynchronously."

**Read path diagram:** Client → Read service → *(read request)* → Cache → *(get key)* → *(value)* → *(response)*; on miss: → Database → *(load on miss, then fill)*. "The read path answers from the cache and only touches the database on a miss."

Message queues are essential for scaling write handling: producers insert, consumers retrieve/process asynchronously — necessary because producers/consumers run at different speeds (buffering) and to prevent data loss during failures (fault tolerance).

## Main Components

Each component section follows: (1) the problem it solves and when to use it, (2) how it works technically, (3) common implementations with trade-offs.

### Microservices

**The Problem.** A monolithic e-commerce app (product catalog, checkout, payments, accounts) works at 100 orders/day. At 10,000 orders/day, you can't scale just checkout — you must scale the whole monolith; teams can't deploy independently; one bug can crash everything.

Microservices split the app into independent services (Product, Cart, User, Order, Payment), each deploying, scaling, and failing independently.

```text
API gateway → Product service, Cart service, Order service, Payment service → Product DB, Order DB, Payment DB
```

**How Microservices Work.** Each service is a separate process, communicating via REST/gRPC. No service touches another's database directly. Services register with a **service registry** (Consul, Eureka) for **service discovery**. Services scale independently (e.g., spin up 10 more Order Service instances for Black Friday). **Fault isolation** via circuit breakers prevents cascading failures.

```text
Payment service ⇄ *(REST or gRPC)* ⇄ Order service ⇄ *(REST or gRPC)* ⇄ Inventory service
Order DB, Inventory DB attached respectively.
```

**Common Implementations.** Spring Boot (Java, enterprise, mature tooling, strong typing) · Node.js + Express (lightweight, fast dev, large npm ecosystem, TypeScript teams) · Go + Gin/Echo (high performance, built-in concurrency, real-time bidding / stream processing).

### Relational Databases

**The Problem.** A bank transfer must subtract $100 from A and add $100 to B — both succeed or both fail; partial completion is unacceptable. This needs **consistency**, solved via structured storage and ACID transactions.

**How They Work.** Tables (rows/columns) represent entities; **primary keys** uniquely identify rows; **foreign keys** create relationships. Three relationship types: one-to-one, one-to-many, many-to-many (via junction table).

**ER-style**

```text
Users (user_id PK, name, email) —1:*— Orders (order_id PK, user_id FK, total)
```

**ACID:** Atomic (all-or-nothing) · Consistent (valid state transitions) · Isolated (concurrent transactions don't interfere) · Durable (persists after crashes).

SQL enables joins, WHERE filters, GROUP BY aggregation — ideal for complex business logic.

**Common Implementations.** PostgreSQL (feature-rich, JSON/arrays, full-text search, custom functions) · MySQL (widely deployed, strong read performance, simple replication) · Amazon RDS (managed Postgres/MySQL/etc., automatic backups/patches/scaling).

### NoSQL Databases

**The Problem.** Instagram posts have images, captions, hashtags, location, timestamp, nested comments — fitting this into relational tables needs many joins across 5 tables per read, and the schema changes weekly (story reactions, polls), requiring constant migrations.

NoSQL stores flexible, nested data (one document per post) without rigid schemas or joins.

**How They Work — four types (interactive tab widget: Key-value / Document / Column-family / Graph):**

- **Key-value** — giant hash map, O(1) lookup, no cross-value queries. Examples shown: `session:abc123 → { "user_id": 456, "exp": ... }`, `cart:u456 → ["sku_1", "sku_9"]`, `rate:ip:1.2.3.4 → 27`. Best for caching, sessions. Examples: Redis · DynamoDB.
- **Document** — self-contained JSON-like documents with nested fields. MongoDB. Use for content management with varying schemas.
- **Column-family** — organized by column not row. Cassandra, efficient for time-series. Use for high-write workloads like logs/analytics.
- **Graph** — nodes and edges as first-class citizens. Neo4j. Use for social networks and recommendation engines.

NoSQL trades ACID for availability/partition tolerance — mostly **eventual consistency** (writes propagate within seconds); strong consistency optional but reduces availability.

**Common Implementations.** MongoDB (rich queries, varying schemas, product catalogs) · Redis (sub-ms latency, lists/sets/sorted sets, caching/leaderboards/rate limiting) · Apache Cassandra (no single point of failure, millions of writes/sec, time-series/logging) · Amazon DynamoDB (fully managed, auto-scales, single-digit ms latency).

### Object Storage

**The Problem.** Netflix stores 100M video files; profile pictures, thumbnails vary from KB to GB. Relational databases are inefficient for this; a single file-system server doesn't scale and replication/partitioning become complex.

Object storage treats each file as an object with a unique key (e.g. `videos/user123/vacation.mp4`), with the system handling distribution/replication/scaling automatically. Use for static assets, backups, data lakes — not for frequently-updated files or low-latency ops.

**How It Works.** Objects live in **buckets**; each object = unique key + binary data + metadata. No real folders — keys just look like paths (flat namespace). REST API: PUT/GET/DELETE.

```text
Client → *(PUT object: key + bytes + metadata)* → Bucket
```

Replicates across 3+ nodes/regions automatically for durability. **Versioning** keeps old versions accessible after overwrite. **Lifecycle policies** auto-tier or delete objects after a set time. Consistency varies — S3 offers strong read-after-write consistency.

**Common Implementations.** Amazon S3 (industry standard, unlimited storage, AWS integration) · Google Cloud Storage (multi-regional, strong consistency, BigQuery integration) · Azure Blob Storage (hot/cool/archive tiers).

### Cache

**The Problem.** An e-commerce site's DB handles 1,000 req/s, mostly the same 100 popular products; 50ms queries hit 90% CPU. Caching serves repeats from fast memory: response drops from 50ms → 1ms, DB load drops 80%.

**How Caches Work.** App checks cache before DB. **Cache hit** = return immediately; **cache miss** = query DB, store in cache, return.

```text
App → *(check first)* → Cache → *hit/miss* → Database
```

**LRU step-through widget:** slider "Cache size: 3 slots"; controls "▶ Step / ▶ Play / Reset"; state shown: Request —, Cache (most recently used on the left), Hits 0, Misses 0, Hit rate 0%. "On a miss the app reads the database, then fills the cache. When the cache is full, the least recently used key is evicted. Repeated keys become hits."

This is the **cache-aside** pattern (app manages cache explicitly). Alternatives: **write-through** (writes update cache + DB simultaneously), **write-behind** (writes hit cache first, async sync to DB later).

**Eviction policies:** LRU (least recently used) · LFU (least frequently used) · TTL (time to live).

**Cache invalidation:** set TTL (e.g. 5 min for prices) or invalidate explicitly on critical updates. Trade-off: longer TTL = more staleness, less DB load; shorter TTL = fresher data, more DB hits.

**Common Implementations.** Redis (sub-ms, rich data structures, sessions/leaderboards/rate limiting) · Memcached (simpler, faster for basic KV) · CDNs like Cloudflare (distributed cache for static assets).

### CDN (Content Delivery Network)

**The Problem.** A video hosted in NYC serving a Tokyo user travels 11,000 km round trip — 200ms latency alone, plus a viral video overwhelms the 10,000 req/s origin server.

CDNs cache static content on globally distributed edge servers — Tokyo users fetch from a Tokyo edge server (5ms), and edge servers absorb most traffic so origin gets far fewer requests. Use for images/video/CSS/JS, high-traffic global apps.

**How CDNs Work.** Upload to the CDN provider; it replicates to edge servers across continents (LA, London, Tokyo, Sydney, etc). DNS routes users to the nearest edge. **Cache hit** = edge serves instantly (5ms); **cache miss** = edge fetches from origin (200ms), caches locally, serves — subsequent users in that region get hits.

**Edge-hit-rate widget:** controls "Play / Restart"; state: "Requests start cold — every cache is empty." Edge nodes shown: edge·NA (cached: 0), edge·EU (cached: 0), edge·APAC (cached: 0); plus "origin shield" and "origin storage". Counters: requests 0, origin reads 0, cache hit rate 0%.

Files cache per **TTL** (e.g. 24h for a static logo, 5min for frequently updated content); after TTL expires, edge re-fetches from origin.

**Common Implementations.** Cloudflare (global CDN, DDoS protection/WAF, free tiers) · AWS CloudFront (tight AWS/S3/Lambda@Edge integration, custom cache behaviors) · Akamai (largest edge network, image optimization, predictive prefetching).

### Message Queues

**The Problem.** Black Friday: 10,000 orders/min; Order Service waits 2s per payment before accepting the next order — orders pile up, users see timeouts. If Payment Service crashes for 30s, 5,000 orders arrive with no retry mechanism and all fail — revenue lost.

Message queues decouple producer/consumer: Order Service publishes to a queue and responds immediately; Payment Service processes at its own pace; if it crashes, messages wait and resume on recovery — no orders lost.

**How They Work.** **Producers** publish messages (e.g. `{"order_id": 123, "user_id": 456, "total": 99.99}`). The **queue** stores messages durably (survives crashes). **Consumers** pull and process messages, then send an **acknowledgment**; the queue deletes acknowledged messages.

**Delivery widget:** controls "▶ Play / Step / + Enqueue"; toggle "Consumer: healthy" (vs failing); "Reset". State: Queue shows delivery-attempt badges (1, 2, 3); Consumer "idle"; Dead-letter queue "empty"; Processed 0. "Press play. The consumer pulls one message at a time." Flipping the consumer to **failing**: unacked messages are redelivered, and after 3 attempts land in the dead-letter queue instead of blocking the line — at-least-once delivery is why consumers must be idempotent.

If processing fails without an ack, the queue re-delivers to another consumer — **at-least-once delivery**. **Dead letter queues** catch poison messages after repeated failures, for manual inspection. **FIFO queues** guarantee order (e.g., same-user messages processed in sequence); standard queues allow out-of-order processing for higher throughput.

**Common Implementations.** RabbitMQ (complex routing, flexible exchange types) · Apache Kafka (distributed event streaming, millions of msgs/sec, retains for replay, event sourcing/real-time analytics/log aggregation) · AWS SQS (fully managed, zero ops overhead, auto-scales).

### API Gateway

**The Problem.** A mobile app talking to 12 microservices manages 12 auth schemes and 12 retry strategies; loading the home screen needs 5 sequential calls (500ms total); with no rate limiting, 10,000 req/s from a malicious user crashes the Order Service.

API Gateways sit between clients and microservices: one endpoint, uniform auth, rate limits, and multi-backend aggregation.

**How They Work.** Clients call `https://api.example.com`; the gateway routes by path/method (`GET /users/123` → User Service, `POST /orders` → Order Service).

Cross-cutting concerns enforced at the gateway: **Authentication** (verify JWT before forwarding), **Rate limiting** (e.g. 100 req/min/user, 429 if exceeded), **Caching** (e.g. cache product details 5 min).

**Request aggregation:** `GET /home` → gateway calls User/Product/Cart services in parallel and combines into one JSON response (1 request replaces 3).

**Load balancing:** round-robins across service instances. Centralized logging tracks all API traffic (timestamps, response times, error rates).

**Common Implementations.** AWS API Gateway (managed, integrates with Lambda/DynamoDB, auth/throttling/caching built-in) · Kong (open-source on NGINX, plugin system) · NGINX Plus (reverse proxy + gateway, high performance, fine-grained routing/caching control).

## Problem Decomposition Framework

Vague problem statements ("Design Twitter") get decomposed via three steps, each extracting different information.

### Step 0: Verbs → Use Cases

Verbs reveal operations — "post," "view," etc. — mapping to CRUD (create, read, update, delete, search, notify, process). Beyond identifying operations, define what "correct" means for each: Does "post tweet" need deduplication? Should followers see tweets immediately or eventually? Must deleted tweets be recoverable?

### Step 1: Nouns → Entities and Ownership

Nouns reveal data models and relationships. For each entity, identify the source of truth / write authority (e.g., only the User Service updates user profiles). Clear ownership prevents conflicting writes and establishes consistency boundaries.

### Step 2: Adjectives → Constraints and Add-ons

Adjectives reveal non-functional requirements forcing architecture choices:

- **instant/realtime** → push notifications, WebSockets, caching, precomputation
- **reliable** → retries, idempotency, dead letter queues, write-ahead logs
- **highly available** → replication, stateless services, health checks
- **auditable/secure** → encryption, access control, audit logs, compliance
- **scalable** → partitioning, read replicas, message queues, horizontal scaling

The goal is not cramming in every technology — it's justifying each addition by tying it to a specific constraint.

## Interview Step-by-Step

Demonstrated by solving **Design Twitter** with the decomposition framework.

### Step 0: Identify Use Cases from Verbs

Users **post** tweets, **view** feed, **follow** users, **like** tweets, **comment** on tweets.

- **Post** — create a new tweet
- **View** — read individual tweets or feeds
- **Follow** — create a relationship between users
- **Like** — increment engagement counter on a tweet
- **Comment** — create a response attached to a tweet

Assume no deduplication of identical tweet content unless specified; deleted tweets need not be recoverable for this problem. Interviews last 45-60 min — five use cases is sufficient.

### Step 1: Identify Entities from Nouns

- **User** — account with profile info — owned by User Service / User Database
- **Tweet** — message with content, timestamp, author — owned by Tweet Service / Tweet Database
- **Follow** — relationship between follower/followee — owned by Follow Service / Follow Database
- **Engagement** (likes + comments) — owned by Engagement Service / Engagement Database

Clear ownership avoids ambiguity about which write wins when two services try to update the same tweet.

### Step 2: Identify Constraints from Adjectives

Twitter should have "low latency" responses, "high availability," "scalable" growth, "durable" storage.

- **Low latency** → precompute feeds into Redis, read from cache instead of querying DB per request.
- **Highly available** → message queues (Kafka) buffer writes so a Tweet Service crash doesn't lose posted tweets.
- **Scalable** → horizontal scaling with load balancers + database partitioning (400M MAU exceeds any single server).
- **Durable** → distributed databases with replication (DynamoDB, Spanner, Cassandra) plus regular snapshots/backups.

**Design-checkpoint widget (multiple choice):** *"An interviewer says the feed must be 'highly available' but never mentions latency. Which addition is justified first?"* Options: (a) *A cache, because caches always help*; (b) *Message queues and replication, because availability is about surviving partial failure* — the framework's reasoning points to (b).

### API Design

One endpoint per functional requirement. Interviewers look for readable paths, clear data types, correct HTTP methods.

- `POST /tweet` — post a tweet (author comes from the authenticated session, not the request body)
- `GET /tweet/{id}` — view a tweet
- `GET /feed` — view feed
- `POST /follow` — follow a user
- `POST /tweet/like` — like a tweet
- `POST /tweet/comment` — comment on a tweet

*(Each listed with a "Request & response" expandable detail in the live UI.)*

### High-Level Design

> Each build step below adds one component to solve one problem; in the live UI a pink **NEW** badge marks the component added at that step.

**Basic Data Flow:** each use case maps to a service in front of its own database (post/read tweets, follow users, view feeds, like/comment). Likes and comments are similar enough (both counters on tweets) to merge into one **Engagement Service** + **Engagement Database**. With multiple services, add an **API Gateway**.

**Applying Constraints** (in order, each justified by the constraint it solves):

1. **Scalability** — deploy multiple instances of each service (Tweet, Feed, Follow, Engagement) behind a **load balancer**, which also does health checks (bonus: availability) and lets you scale each service independently with traffic.
2. **Low latency** — add a **cache** (Redis/Memcached) in the Feed Service; posting a tweet pushes it into followers' precomputed feed cache instead of recomputing from the DB on every read; cache freshness via TTL or event-driven invalidation.
3. **High availability** — add a **message queue**: Tweet Service enqueues new tweets instead of calling Feed Service directly; consumers update DB + cache; if Tweet Service goes down, queued messages still get processed once it — or another consumer — recovers.
4. **Durability** — use **distributed databases** (DynamoDB, Spanner, Cassandra) with automatic replication plus regular snapshots/backups for Tweet DB, Follow DB, Engagement DB.

### Deep Dives

**How would you handle the Celebrity Problem?** Users with millions of followers create massive fan-out on post, overwhelming systems.

**Push/hybrid toggle widget:** options "Pure push" / "Hybrid (T = 100K)"; slider "Producer followers: 6K"; readout "Strategy for this author: Push on write", "Timeline writes per post: 6K" — "Push copies this post into 6K timelines at write time, so reads stay cheap."

Solution: normal users keep fan-out-on-write (push at post time); users above a follower threshold (e.g. 10,000) switch to fan-out-on-read — their tweets are stored but not pre-pushed into every follower's feed; the Feed Service dynamically merges celebrity tweets in at read time.

**What separates answers on celebrity fan-out (expandable rating list):**
- **Bad** — Pure fan-out-on-write for everyone
- **Good** — Hybrid push/pull with a follower threshold
- **Great** — Hybrid, plus a cache for the pulled hot keys

**How would you efficiently support Trends and Hashtags?** Each region computes local trends via sliding-window aggregation (past 15 min) over hashtags/keywords; local results feed a global aggregation service. Tweet Service indexes hashtags on creation into an inverted index (Elasticsearch/Solr). Trends recompute periodically (e.g. every minute) and cache in Redis with a TTL.

**How would you handle Tweet Search at Scale?** Tweet Service sends new tweets to search-indexing services via message queues; indexers update Elasticsearch/Solr. Partition search indexes by time (daily) or hashtag; older indices move to slower/cheaper storage. Use inverted indexing plus ranking (BM25 or ML-based) by engagement/recency. Cache popular search queries/results.

## Test Your Understanding

**Quiz widget** ("Hide All" / "Reveal All" toggle) — 5 questions, each with a "Show/Hide Answer" button. Full text of every question and its revealed answer:

**1) Why should you reveal architecture components one at a time in an interview instead of drawing the full diagram up front?**
Each component should be earned by a concrete problem. Adding boxes one at a time shows deductive reasoning — you introduce a cache because reads hammer the database, not because caches are generically good. A full diagram up front reads as memorization, and interviewers probe memorized answers until they break.

**2) Plain hash-mod-N sharding and consistent hashing both spread keys across nodes. Why prefer consistent hashing when the cluster resizes?**
With hash-mod-N, changing the node count changes the modulus, so almost every key maps to a different node — a near-total reshuffle. Consistent hashing places keys and nodes on a ring, so adding or removing a node moves only the keys in one arc, about 1/N of them. That makes rebalancing cheap.

**3) During a network partition, what does an AP system do that a CP system does not?**
An AP system stays available by serving possibly stale data on both sides of the partition. A CP system refuses requests on the stale side to avoid returning inconsistent data. The choice is forced: during a partition you cannot be both fully consistent and fully available.

**4) Two clients increment the same counter concurrently and one update is lost. How does optimistic locking prevent this?**
Each write carries the version it read: `UPDATE … WHERE version = N`. The first write succeeds and bumps the version. The second matches zero rows because the version has moved, so it re-reads the fresh value and retries. No update is silently overwritten.

**5) Why does a message queue require workers to be idempotent?**
A queue guarantees at-least-once delivery: if a worker crashes before acknowledging, the message is redelivered and processed again. If processing is idempotent, repeating it has no extra effect — no double charge, no duplicate row.

---

### System Design Master Template (embedded video)

YouTube embed: https://www.youtube.com/embed/OWVaX_cBrh8?si=MAfaQS1TV1r7USUI

### Comments (as of scrape date)

- **Michael Moon** (Jun 09 2026): "Amazing content"
- **falase femi** (Apr 01 2026): "very well detailed"
- **Pawan Pawar** (Jan 02 2026): "Well written primer! 👏🏻"
- **Manjunath A** (Nov 28 2025): "Every helpfull"
- **mohan nair** (Sep 24 2025): "How is the GET request for tweets returning the likes and comments since those are stored in Engagement DB as per the HLD? GET /tweet/<id>"
- **PLuna** (Jul 31 2025): "This is awesome. Thank you so much for posting this. One thing that confuses me is how to get good at the API Design part..."
- **Don Mamaril** (Jul 20 2025): "This was an excellent primer. Thank you!"
- **Ank7T** (Jun 12 2025): quoting the primer's tweet-loss scenario and noting the feed-cache write actually covers it
- **Mosharaf Hossain** (Jun 10 2025): "Supper nice explanation - short, sweet, and to the point. Thank you"
- **André Ferraz** (Jun 01 2025): "Nice!"

---

## Assets

No downloadable diagram image files exist on this page — every diagram is a live JS/SVG widget, fully transcribed above. The only real `<img>` on the page is the site's decorative header logo (`/logo.svg`); it could not be retrieved in this sandbox (outbound fetches to systemdesignschool.io are limited to the page-text/browser tools, which don't return raw binary/SVG bytes) but it is purely cosmetic and carries no article content.
