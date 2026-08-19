# Design a Distributed Rate Limiter

Source: https://systemdesignschool.io/problems/rate-limiter/solution

> Note on fidelity: this page is built from many JS-interactive widgets (sliders, step-through diagrams, tabbed panels, animated simulations, a "before reading on" prompt per deep dive, and a quiz with click-to-reveal answers) rather than static images. Every widget's full content — including states behind tabs/toggles, the labels/boxes/arrows inside each diagram, the expandable API request/response bodies, and all quiz answers — has been clicked through (via the live page, with "Reveal All" and both "Request & response" toggles expanded) and transcribed below as text, in the same order it appears on the site. The site has no downloadable diagram image files (rendered live by JS/SVG, not `<img>` files), so there are no image assets to save for this page.

Tags: Medium difficulty · Rate limiting · Atomic counters · Sliding window · Token bucket · Availability · Hot key

---

## Problem statement

Design a service that decides, for each incoming API request, whether to allow it or reject it because the caller has exceeded a configured rate. The limiter sits in front of an API gateway or an internal RPC fabric, must answer in single-digit milliseconds, and must keep working when individual nodes — or an entire datacenter — fail.

The naive single-process counter falls over once two app servers sit behind a load balancer. The rest of the design is about what to do next. Adjacent problems — quotas, billing, and DDoS defense — are separate systems and out of scope here.

## Clarifying questions

Several parameters shape the architecture. Each question below settles one, paired with the assumption its answer fixes.

- **What is the limit keyed on?** User id, API key, IP, tenant, endpoint, or a tuple. The key's cardinality drives storage size and shard layout.
- **What is the limit, and over what window?** "100 per second" and "100 per minute" need different algorithms even at the same average — bursts behave differently.
- **How accurate must it be?** A hard cap (overage costs money), a soft cap (protect a backend), or a coarse throttle (keep noisy neighbors from drowning the cluster).
- **What QPS hits the limiter itself?** Fronting a high-traffic public API is a different system than fronting a low-traffic internal service.
- **Single-region or multi-region?** A global limit across regions is strictly harder and may require accepting overage by design.
- **Fail-open or fail-closed?** When the limiter is down, let traffic through or reject it? A product decision, not a technical one.
- **What does the client see on rejection?** An HTTP 429, a `Retry-After`, the current quota? Surfacing state lets clients self-regulate; hiding it forces blind retries.

Each answer changes the architecture, so each is settled before designing.

## What makes a rate limiter hard

A rate limiter looks like a counter: increment on each request, deny past the limit. On one server that is a few lines of code. The problem is that real traffic arrives at *many* servers behind a load balancer, and they all have to agree.

The thing that shapes the architecture is that the limit is one shared counter every app server must agree on, at single-digit-millisecond latency. So the driving tension is **accuracy versus the coordination-and-latency cost of agreeing**. Push for exact accuracy and every decision waits on a shared store; push for speed and each server counts locally and the limit drifts.

> **Atomic read-modify-write.** Reading a counter, deciding, and writing the new value as one indivisible step. If two servers read `99` at the same time, both think they are under a limit of 100, and both allow — the limit silently doubles.

```text
Client ──▶ API gateway ──(allow?)──▶ Limiter service ──(atomic incr)──▶ Counter store
                                            │
                                            ▼
                                   allow / deny (429 on deny)
```
This diagram is a live interactive SVG widget on the site, not a static image. It shows the atomic decision path: client request through the API gateway to the limiter service, which does an atomic increment against the counter store and returns allow or deny (429 on deny).

> **Key idea.** A limiter is a counter primitive plus a coordination strategy; the hard part is agreeing on the count atomically at sub-millisecond latency.

## Key concepts

This section covers the concepts needed to solve this problem — prerequisites for the design work that follows.

A limiter is assembled from two choices: *how* a window is counted, and *where* the count lives.

### Counting a window

Five algorithms recur, trading accuracy for memory. **Fixed window** buckets time into fixed intervals and counts the current bucket — cheap, but it lets a burst straddle the boundary. **Sliding window counter** keeps the current and previous bucket counts and blends them by how far into the window the request falls — O(1) memory, slightly approximate. **Sliding window log** stores a timestamp per request and counts what is newer than `now - window` — exact, but storage grows with the limit. **Token bucket** refills tokens at a fixed rate up to a capacity; each request spends one — allows bursts up to capacity, then enforces the average. **Leaky bucket** is its mirror: requests fill a bucket that drains at a fixed rate, shaping traffic into a steady stream.

| Algorithm | Memory per key | Accuracy | Best for |
|---|---|---|---|
| Fixed window | O(1) | boundary burst | the cheapest coarse cap |
| Sliding window counter | O(1) | ~exact | the default API limit |
| Sliding window log | O(limit) | exact | billing-critical limits |
| Token bucket | O(1) | exact, bursty | when bursts are a feature |
| Leaky bucket | O(1) | smoothed output | feeding a fixed-rate downstream |

**Interactive algorithm-demo widget** (tabs: Fixed window / Sliding counter / Sliding log / Token bucket / Leaky bucket): sliders "Limit per window" (default 8), "Window length" (default 8s), "Arrival rate" (default 2/s); controls "Play" / "Reset"; visualization is a request stream (newest on the right, ● allowed / ● denied dots) plus an algorithm-specific readout — default (token bucket tab shown): "tokens available 8.0 / 8", "allowed 0", "denied 0", "t = 0.0s". Switching algorithms and pressing play shows the fixed window admitting a boundary burst that the sliding counter rejects, and the token bucket admitting a burst that the leaky bucket smooths into a steady rate.

### The shared counter

On one server the counter is a local variable. Across a fleet it must move to a shared store that every limiter instance reads and writes, because per-server counters each see a fraction of traffic and let multiples of the limit through. That shared store is the heart of the design — and its single biggest cost, since every decision pays one network round-trip to reach it.

The failure is structural: each server enforces the full limit against only its slice of traffic, so the real cap becomes `servers × limit`.

```text
                 ┌─▶ Server 1 (local count to limit) ─┐
Load balancer ───┤                                     ├──▶ effective = N × limit
                 └─▶ Server 2 (local count to limit) ─┘
                              │
                          fix │
                              ▼
                      One shared counter
```
This diagram is a live interactive SVG widget on the site, not a static image. It shows the multiplied-limit failure: each server counts only its own slice of traffic against the full limit, so the effective limit becomes N × limit, and the fix is a single shared counter.

### Central versus hybrid coordination

The shared counter can be **central** — one authoritative store, exact, but a round-trip per decision — or **hybrid** — each instance keeps a local approximate counter and syncs with the center on a short interval. Hybrid makes decisions in microseconds but over-allows by a bounded amount during each sync gap. The choice follows from the cost of being wrong — quantified, with the over-allowance math, in §6.2.

### Fail-open versus fail-closed

When the limiter's store is unreachable, the limiter must still answer. **Fail-open** allows traffic — the API stays up but unprotected. **Fail-closed** denies — the backend is protected but the limiter outage becomes an API outage. The right default depends on whether the backend can survive uncapped traffic.

![alt text](image.png)

```text
Counter store unreachable ──▶ Failure policy ──┬──▶ fail-open  → Allow all (API up, backend exposed)
                                                └──▶ fail-closed → Deny all (backend safe, API down)
```


> **Key idea.** Choose an algorithm for *how* you count and a coordination strategy for *where* the count lives; the two axes tune independently.

## 1. Requirements

> **Before reading on.** List the functional and non-functional requirements, then name the one property you would never compromise and the one constraint that drives the design. They are different here.

### 1.1 Functional requirements

The limiter answers one question per request and is configured out of band.

- **Decide** allow or deny synchronously for each `(key, action)` pair.
- **Configure** limits per key, varying by API tier, endpoint, and tenant.
- **Enforce multiple windows per key.** A key often has a per-second *and* a per-day limit; deny on the tighter binding constraint.
- **Surface state.** On allow, return remaining quota and reset time; on deny, return enough for the gateway to send a 429 with `Retry-After`.

### 1.2 Non-functional requirements

The qualities matter more than the feature list — a limiter that is slow or fragile is worse than none.

- **Sub-millisecond p99** added latency in the same datacenter; the limiter cannot become the bottleneck.
- **High availability** — at least four nines, degrading gracefully when nodes or datacenters fail.
- **Horizontal scalability** across instances with a sharding strategy.
- **Bounded inaccuracy** — steady-state limits roughly correct; small boundary error under bursts is tolerable for soft limits.

### 1.3 The constraint versus the property

**Availability is the property to protect**: the limiter fronts every request, so it must keep answering even when its store is degraded — which is why fail-open versus fail-closed is its own decision. **Latency is the constraint that drives the design**: a sub-millisecond budget for the limiter's *own* added work (the single-digit-millisecond target in the problem statement is the end-to-end budget, including the backend) forces the atomic-operation choice and the central-versus-hybrid split. Accuracy is what is traded against that budget.

> **Key idea.** Availability is the property to protect; the per-decision latency budget is the constraint to design around; accuracy is what is traded against it.

## 2. Back-of-the-envelope estimation

The numbers exist to size two things: how many shards the counter store needs, and how much a hybrid design over-allows. The figures are illustrative anchors, not measured values.

**Interactive estimation widget (default inputs):**

| Input | Default |
|---|---|
| Decisions / sec | 1.0M |
| Counter checks / request | 2 |
| Per-shard ceiling | 100K/s |
| Hybrid: instances | 10 |
| Hybrid: req/s per instance | 1K |
| Hybrid: sync interval | 100ms |

**Computed outputs:**

| Output | Value | Formula shown |
|---|---|---|
| Counter shards | 20 | 2.0M ops/s ÷ 100K |
| Hybrid over-allowance | 1K extra reqs per sync gap | over-allow = 10 × 1K/s × 0.10s = 1K |

A single central shard tops out near its ceiling, so high decision rates force sharding by key. Going hybrid trades a bounded over-allowance per sync gap for microsecond local decisions — fine for soft limits, not for billing.

### 2.1 Throughput and shards

A single in-memory counter node handles on the order of **100K simple atomic operations per second** — fewer for heavier scripts. A limiter fronting a public API doing 1,000,000 decisions per second, each checking a per-second *and* a per-day window, issues about 2,000,000 counter operations per second. That is `2,000,000 ÷ 100,000 ≈ 20+ shards`, and 10× the traffic pushes past 100 shards. At this load the counter store has to shard by key.

### 2.2 Memory

State per key is tiny for most algorithms. Ten million active keys with a sliding window counter at two integers each is `10M × 24 bytes ≈ 240 MB` — a single node holds it many times over. The sliding window log is the outlier: at 100 requests per minute it stores up to 100 timestamps per key, `10M × ~5 KB ≈ 50 GB` (up to ~100 GB with overhead), which is the dominant storage cost on that path, especially once replicated.

### 2.3 Over-allowance under hybrid

In the hybrid pattern, each instance decides against its own local counter and reconciles with the central store only every sync interval. Between syncs, no instance sees the others' traffic, so each can independently believe it is under the limit — and the resulting error is computable. With 10 instances syncing every 100ms, each seeing 1,000 requests per second for a key limited to 500 per second, the worst case is every instance believing it is under the limit at once: `10 instances × 1,000 req/s × 0.1s = 1,000 extra requests` in the gap before the next sync corrects them — a 200% overshoot for one window that asymptotes to the correct rate over time. Fine for a soft limit; unacceptable when each call costs money.

> **Key idea.** The two numbers that shape the design are the shard count (decision rate ÷ per-shard ceiling) and the hybrid over-allowance (fleet × rate × sync interval).

## 3. API design

**Design checkpoint widget:** *"A limiter decides allow-or-deny. When it denies, should the limiter service itself return HTTP 429, or return 200 with a verdict the gateway acts on?"* Options presented: (a) *The limiter returns 429 directly so the client sees the rejection*; (b) *The limiter returns 200 with `allowed:false`, and the gateway turns a deny into 429*. (The page's own "Key idea" below §3 states the limiter always returns 200 and the gateway owns turning a deny into a 429 — i.e. option (b).)

The interface is small: one hot-path call to check a limit, one control-plane call to configure one. The check carries a `cost` so a heavy endpoint can spend more than one token per request.

### 3.1 Check a limit

The gateway calls this on every request. It returns the verdict plus the state the gateway needs to build response headers.

`POST /v1/limits:check`

**Request & response (expanded):**

Request body:
```
{
  key,     // api_key:7f3a... | user:... | ip:...
  action,  // POST /messages
  cost     // request weight, default 1
}
```

Response body:
```
{
  allowed,
  remaining,
  limit,
  window_seconds,
  reset_at,
  retry_after_seconds   // present when denied
}
```

### 3.2 Configure a limit

Limits are set out of band and refreshed by the limiter on an interval, never read from this call on the hot path.

`PUT /v1/limits/{key}/{action}`

**Request & response (expanded):**

Request body:
```
{
  max,
  window_seconds,
  algorithm   // sliding_window_counter | token_bucket | ...
}
```

Response body: `204 No Content`

> **Key idea.** The limiter answers a question and always returns 200; the gateway owns the client connection and turns a deny into a 429 with `Retry-After`.

## 4. Data model

The data model begins with the simplest counter the problem needs; the window algorithm and the lifetime requirement each reshape it.

### 4.1 The counter, shaped by algorithm

The base state is `key → count`. The window algorithm decides the exact shape: a fixed window keys by `(key, window_start)`; a sliding window counter holds the current and previous counts; a sliding window log holds a sorted set of timestamps; a token bucket holds the token balance and the last refill time.

![alt text](image-1.png)

This diagram is a live interactive SVG widget on the site, not a static image. It shows the three schema variants for the counter, shaped by algorithm:
- `FixedWindow { string key; int window_start; int count }`
- `SlidingCounter { string key; int current_count; int previous_count }`
- `TokenBucket { string key; double tokens; timestamp last_refill }`

### 4.2 Lifetime and placement

Every entry carries a TTL equal to its window, so idle keys evict themselves (`SET key value EX window_seconds`) and memory stays bounded by *active* keys, not all keys ever seen. The state lives in a single shared in-memory store, accessed only by exact-match key — there are no range queries, which is what lets the store shard cleanly by key hash.

> **Key idea.** The counter's shape follows the algorithm, and a per-window TTL bounds memory to active keys without any cleanup job.

## 5. High-level design

The design is easiest to follow when built from the smallest version that could work, letting each failure pull in the next box.

> **Reading the diagrams.** Each step marks the components newly added at that step with a dashed outline and a **NEW** badge, so you can see what changed from the step before.

### 5.1 A counter in the app server

The simplest limiter is an in-process counter per key: increment on each request, reset every window, deny past the limit. On one server it is correct.

```text
Client ──▶ App server (in-process counter) ──▶ Backend API
```
step 1: the simplest limiter, an in-process counter inside a single app server.

This single-server design fails as soon as a load balancer puts two servers behind it. Each server sees half the traffic and counts only its own half. With a 100-per-second limit and two servers, each sees 100 per second, each thinks it is exactly at the limit, and together they let 200 through. The limit is broken.

### 5.2 Fix 1: move the counter to a shared store

The count has to live somewhere both servers see. It moves to a **counter store**: the limiter becomes a **stateless service** that reads and writes that store, and every app server calls it through a thin **limiter client**.

![alt text](image-2.png)

```text
Client ──▶ App server ──▶ Limiter client [NEW] ──▶ Limiter service [NEW] ──▶ Counter store [NEW]
```
This diagram is a live interactive SVG widget on the site, not a static image. It shows step 2: the counter moves to a shared counter store behind a stateless limiter service, reached through a thin limiter client.

Now both servers share one count. But a new failure appears: two limiter instances can read the same value before either writes it back.

### 5.3 Fix 2: make the read-modify-write atomic

Two instances both read `99`, both decide "under 100," both write `100`, and both allow — a classic race. The fix is to make read, decide, and write one indivisible operation. An in-memory store that processes commands one at a time provides exactly this: while one request's increment runs, no other can slip between its read and its write, so a bare `INCR` needs no locking. Multi-step logic runs as a small server-side **script** that executes start to finish with nothing interleaved.

![alt text](image-3.png)
This diagram is a live interactive SVG widget on the site, not a static image. It shows step 3: the read-modify-write becomes one atomic server-side script (INCR + EXPIRE) against the counter store.

The script increments, sets the TTL on first touch, and returns the new count in one shot. The race is gone — but the store is now a single point of failure on the path of every request.

### 5.4 Fix 3: remove the single point of failure

If the counter store dies, every limiter decision dies with it, and so does every service behind it. A **replica with automatic failover** makes a primary loss a seconds-long blip rather than an outage. Two more boxes belong off the hot path: a **config store** the limiter refreshes every 30 seconds (never read per request), and a **telemetry pipeline** that receives per-decision events asynchronously (never blocking a decision).

![alt text](image-4.png)

```text
                          (atomic op)                (replication)
Limiter service ─────────────────────▶ Counter primary ─────────────▶ Replica [NEW]
        │
        ├──(refresh 30s)──▶ Config store [NEW]
        │
        └──(async)────────▶ Telemetry pipeline [NEW]
```
This diagram is a live interactive SVG widget on the site, not a static image. It shows step 4: a replicated counter primary with automatic failover, plus a config store refreshed every 30 seconds and an async telemetry pipeline, both off the hot path.

### 5.5 The composed design and its latency budget

The pieces compose into one path: client → gateway → limiter client → limiter service → an atomic op against a sharded, replicated counter store, with config and telemetry alongside.


![alt text](image-5.png)

```text
Client ──(1 request)──▶ API gateway ──(2 check(key,action,cost))──▶ Limiter service ──(3 atomic INCR+EXPIRE)──▶ Counter store
                                                                          │◀──(4 new count)───────────────────────────┘
                                              (5 allowed/denied + remaining)
                                                          │
                                     ┌────────────────────┴────────────────────┐
                              (6) if denied                              (7) if allowed
                          429 + Retry-After                          forward to backend
```
This diagram is a live interactive SVG widget on the site, not a static image. It shows the composed sequence: client through gateway to limiter service to counter store, with the branch on deny (429 + Retry-After) versus allow (forward to backend).

The whole decision is one in-datacenter round-trip — roughly half a millisecond — plus the script's own microseconds. That is the latency floor the constraint demanded, and the reason the next decisions focus on minimizing or bounding that round-trip.

> **Key idea.** Each component answers one failure of the naive design: a shared store for many servers, an atomic script for the race, a replica for the single point of failure, and config and telemetry kept off the hot path.

## 6. Deep dives

Four decisions shape the limiter: which window algorithm to count with, whether to keep the counter central or go hybrid, how to behave when things fail, and how to observe what the limiter is doing.

### 6.1 Choosing the window algorithm

> **Before reading on.** A client sends 100 requests at 00:00:59 and 100 more at 00:01:01, under a limit of 100 per minute. No single fixed window is violated. Why is that a problem, and which algorithm catches it?

A fixed window counts only the current bucket, so those two bursts sit in different buckets and both pass — 200 requests in two seconds, double the intended rate, the **boundary burst**. A sliding window counter weights the previous bucket's count by how much of the current window has not yet elapsed, then adds the current bucket. Just 3% into the new window (`elapsed = 0.03`), the previous 100 still counts almost fully: `100 × (1 − 0.03) + 100 ≈ 197`, above the limit of 100, so the request is denied — catching the boundary burst the fixed window let through.

![alt text](image-6.png)

This diagram is a live interactive SVG widget on the site, not a static image. It shows the boundary burst: 100 requests just before a fixed window boundary and 100 more just after, so 200 requests land in two seconds without either window individually exceeding its limit.

The animated widget in Key concepts shows this directly: under the same burst, the fixed window allows it while the sliding counter denies it.

The sliding window counter is the right default for API limits: O(1) memory and a measured error around 0.003% across hundreds of millions of requests ([Cloudflare](https://blog.cloudflare.com/counting-things-a-lot-of-different-things/)). The **sliding window log** fits only when the count must be exact — billing-critical limits — at the cost of a timestamp per request. The **token bucket** fits when bursts are a feature, not a bug: it lets a client spend a saved-up balance (say, up to 50 at once) and then enforces the average refill rate. Leaky bucket is its mirror — the same single-counter cost, but it smooths output into a steady stream instead of allowing bursts, so it fits when the downstream has a fixed throughput.

A key usually carries more than one limit at once — say **100/sec and 10,000/day** — and a single request must honor both. The check increments *both* counters inside one atomic script and denies if **either** window is over its max; the request passes only when both do. The response reports the **binding** window — the one nearest its limit — so `remaining` is the smaller of the two, and on a denial `retry_after_seconds` comes from the **soonest reset among the violated windows**: if only the per-second window is over, the client waits about a second, not until midnight. Putting both increments in the same script keeps the windows consistent — a request never counts against the daily limit without also counting against the per-second one.

![alt text](image-7.png)

```text
Request ──▶ one atomic script: INCR per-second AND per-day ──▶ either over its max?
                                                                  ├── yes ──▶ deny (retry_after = soonest reset of violated window)
                                                                  └── no ───▶ allow (remaining = min across windows)
```
This diagram is a live interactive SVG widget on the site, not a static image. It shows the dual-window check: one atomic script increments both the per-second and per-day counters, denying if either is over its max and reporting the binding window.

**What separates answers — the algorithm:**
- **Bad** — One algorithm, no awareness of boundary error
- **Good** — Sliding window counter as default, log for exactness
- **Great** — Matches algorithm to the cost of being wrong

### 6.2 Central versus hybrid counting

> **Before reading on.** A central counter is exact but costs one round-trip per decision. How do you get a microsecond decision without giving up on a limit that is roughly right?

A central counter is the simplest correct design: one authoritative store, every decision an atomic op against it. Its floor is one round-trip — about half a millisecond in-datacenter — which is fine until that floor is too high or the shard is too hot. The **hybrid** pattern keeps a local counter per key on each instance and syncs a delta with the center on a short interval, reading back the global state. Decisions become local and microsecond-fast; the cost is bounded over-allowance during each sync gap.

![alt text](image-8.png)

```text
Central — exact, one round-trip           Hybrid — local decide, periodic sync
                                    
   Instance ─┐                              Instance (local counter) ─┐
   Instance ─┼─▶ Counter store               Instance (local counter) ─┼─(sync delta)─▶ Global counter
   Instance ─┘                              
```
This diagram is a live interactive SVG widget on the site, not a static image. It shows two coordination modes: central, where every instance round-trips to one exact counter store, versus hybrid, where each instance decides locally and periodically syncs a delta with the global counter.

The over-allowance scales with `fleet size × per-instance rate × sync interval`, the number the estimation widget computes. A **token-leasing** variant tightens it further: the central store hands each instance a lease — say 1/N of the limit — which the instance burns locally and refills when depleted.

For example, the limit is 1,000 requests/sec across 4 instances. Each leases 250 tokens from the central store up front and then decides locally, with no network on the hot path. Instance A catches a burst and burns its 250 in 40ms, so it asks the center for 250 more. The center keeps one piece of state — how many of the 1,000 are currently leased out — and grants the refill only if the global budget still has room; if all 1,000 are already leased, A is denied until a lease frees up. The bound is different from periodic sync *because* an instance can over-spend at most its own outstanding lease before it must ask the center — not a whole sync interval of traffic it never saw. So total over-allowance is bounded by `N × lease size`, independent of request rate.

![alt text](image-9.png)

```text
Instance A (lease of 250, 250 used in 40ms) ──(burn locally)
                                              ──(ask for 250 more)──▶ Central store (tracks leased vs free of 1000)
                                                                            │
                                                              grant if budget free / else deny
                                                                            │
                                                                            ▼
                                                        Instance A (429 until a lease frees)
```
This diagram is a live interactive SVG widget on the site, not a static image. It shows token leasing: an instance burns its leased tokens locally and asks the central store for more only when depleted, with the center granting a refill only if global budget remains.

The spectrum runs from central-exact through local-cache, periodic-sync, and token-leasing to sketch-based estimates; the design states where it sits on this spectrum and why.

**What separates answers — coordination:**
- **Bad** — Central store, no latency or hot-shard awareness
- **Good** — Central default with the latency floor acknowledged
- **Great** — Names the spectrum, picks one with over-allowance math

### 6.3 Failure: atomicity, fail-open, and the retry storm

> **Before reading on.** The counter store fails over for three seconds. During those three seconds, should the limiter allow everything or deny everything — and how do you keep a denied client from making the outage worse?

Three failure modes stack here, and each has its own fix.

The first is the **race**, already met in the HLD: do the read-modify-write as one atomic script (`INCR` then `EXPIRE` together), never as two separate commands with a check in between. The second is the **store outage**. A primary loss takes the limiter — and every service behind it — down unless a replica fails over automatically, and even then there is a seconds-long window where decisions cannot reach fresh state. That window forces the fail-open versus fail-closed choice. **Failing open by default** suits ordinary throttling — a limiter outage should not take the API down with it. **Failing closed** is reserved for cases where uncapped traffic would do more damage than the limiter being unavailable, such as billing limits or a backend that cannot absorb the load.

Fail-open is safer with a backstop. Two tiers provide it: a coarse in-process token bucket at the gateway — per IP, at perhaps 10× the normal rate — that keeps working during a counter-store outage because it needs no network, and the real per-key distributed limiter behind it. A *deny* from either tier stops at the gateway as a 429; an *error* from the distributed tier falls through to the in-process tier, which stops only the worst abuse without taking the API down.

```text
Request ──▶ Tier 1 (in-process, 10× per IP) ──┬── deny  ──▶ 429 at gateway
                                                └── allow ──▶ Tier 2 (distributed per-key) ──┬── deny  ──▶ 429 at gateway
                                                                                               ├── error ──▶ fall through to backend
                                                                                               └── allow ──▶ forward to backend
```
![alt text](image-10.png)

This diagram is a live interactive SVG widget on the site, not a static image. It shows the two-tier fail-open path: a coarse in-process tier at the gateway backstops a distributed per-key tier, where an error in the distributed tier falls through to the backend instead of blocking.

The third mode is the **retry storm**. Denying a busy client that retries immediately replaces one over-limit request with two. A `Retry-After` with a sensible base plus small random jitter prevents this; a single fixed delay re-synchronizes every client into the next spike. Clients that ignore `Retry-After` are a known failure mode; the gateway should track repeat offenders and apply harder backoff.

A fourth pressure is the **hot key** — one popular key whose traffic saturates a single shard, independent of any client misbehavior. The load is spread with **virtual sub-counters**. For example, key `X` takes 100,000 requests/sec against a 10,000/sec limit, far more than one shard can serve. Split the key into 10 sub-counters `X#0 … X#9`, each on a different shard with a `1,000/sec` slice of the limit. Each request hashes to one sub-counter (`hash(request) % 10`) and is allowed or denied by that sub-counter alone. Ten shards now share the 100K/sec firehose at 10K/sec each, and the slices sum to the original limit. The accuracy cost is small skew: if traffic doesn't divide evenly, one sub-counter can fill while another has room, so the effective limit wobbles by a sub-slice — acceptable for throttling, which is why this is for hot *throttling* keys, not billing. Alternatively, route the hot key to the hybrid path so most decisions skip the store entirely.

```text
One hot shard (key X, 100K/sec vs 10K limit)
        │  split into 10 virtual sub-counters, hash-routed
        ▼
  X#0: 1K/sec   X#1: 1K/sec   ...   X#9: 1K/sec
```
![alt text](image-11.png)

This diagram is a live interactive SVG widget on the site, not a static image. It shows hot-key mitigation: a single hot key overwhelming one shard is split into ten hash-routed virtual sub-counters, each holding a slice of the original limit.

**What separates answers — failure:**
- **Bad** — check-then-set, no failure policy
- **Good** — Atomic script, replica failover, a chosen policy
- **Great** — Two-tier fail-open, jittered Retry-After, hot-key routing

### 6.4 Observability and safe rollout

> **Before reading on.** A 429 spike just paged you. Is it correct enforcement of a real abuser, or a limit someone misconfigured? What would you need to have been recording to tell the difference in seconds?

Limiter decisions are silent unless something breaks, which makes three things worth recording: per-key allow-versus-deny counts (to tell a real abuser from a bad config), counter-store health (per-shard latency, error rate, replication lag), and every configuration change (each one is a deploy-equivalent event — old value, new value, who, when). The non-negotiable pattern is to emit these asynchronously and aggregate at one-second resolution before shipping, so observability never sits on the hot path.

![alt text](image-12.png)

```text
Decision ──(async, non-blocking)──▶ Telemetry pipeline ──(1s aggregation)

New limit in shadow mode ──▶ log would-deny, do not deny
```
This diagram is a live interactive SVG widget on the site, not a static image. It shows the shadow-rollout path: decisions are emitted asynchronously to a telemetry pipeline aggregated every second, and a new limit in shadow mode only logs what it would deny without enforcing.

New limits roll out in **shadow mode**: the system logs what *would* be denied without denying anything for a day or two, then enforcement is enabled once the numbers look right. This catches the misconfiguration that would otherwise become an incident the moment the limit goes live.

> **Key idea.** Emit decisions asynchronously and aggregate before shipping, and shadow-test every new limit before it can reject real traffic.

## 7. Variants

For **10× scale** (around 10M decisions per second), the architecture holds but the breakpoints move. Sharding by key with [consistent hashing](https://systemdesignschool.io/fundamentals/consistent-hashing) — which maps keys to shards while moving only a small fraction of keys when shards are added or removed — crosses 100+ shards, and the central round-trip dominates enough that hybrid with token leasing becomes mandatory: 100 instances, a lease of ~1% of the limit refreshed about every second, in-process decisions near 10 microseconds, over-allowance bounded to one lease per instance. Hot-key mitigation stops being optional, per-decision logging is replaced by sampling, and the config store graduates into its own production system with an SLO and change review.

For **one global limit across regions**, accept that synchronous global counting is off the table — a cross-region round-trip is 50–100ms, fatal on a hot path. Split the global limit per region and reconcile asynchronously, or, for daily and monthly limits, use **borrow-and-settle**. For a daily limit, the global budget is 1,000,000 requests/day. At the start of the day each region **borrows** a slice from a global ledger — us-east takes 500K, eu-west 300K, ap-south 200K — and from then on each region enforces only its own slice locally, with no cross-region call on the hot path. After the window closes, regions **settle**: actual usage is reconciled against the ledger. If us-east spent 540K — 40K over its borrow — that overage shrinks tomorrow's borrow (or requires explicit approval), so the global budget stays honored on average even though any single day can drift. It gives eventual global fairness with no cross-region coordination on the hot path — and only works when the settlement window dwarfs the network round-trip, so never for per-second limits.

For **billing-critical limits**, where overage costs money, three things change together: the algorithm becomes the sliding window log for exactness, the counter goes central-exact and never hybrid (a few extra milliseconds per call is an acceptable price for being right), and the failure mode flips to fail-closed. Dedupe on the idempotency key *before* the rate-limit check, so a client's retry does not consume quota twice.

> **Key idea.** The same limiter scales by changing one axis at a time — coordination for raw throughput, regional strategy for global limits, algorithm and failure mode for billing accuracy.

## 8. The transferable pattern

A rate limiter is a **counter primitive plus a coordination strategy**, and almost every hard decision is choosing one of those two independently. Change the coordination — central, hybrid, regional, borrow-and-settle — and the limiter scales across orders of magnitude. Change the algorithm — fixed, sliding counter, sliding log, token bucket — and it trades memory for accuracy. Change the input — per-key, per-endpoint, per-cost-weight — and it bills differently.

That decomposition is what transfers. Most distributed-counter problems — analytics, trending, deduplication, leader election — reduce to the same shape: a shared count that many nodes must agree on, where coordination cost is traded against accuracy. Once the shape is recognized, the design follows from how much coordination is affordable.

## Review: the 30-second answer

The design rests on five decisions, each derived above:

- **Stateless limiter over a shared counter store.** One atomic op per decision, one in-datacenter round-trip.
- **Sliding window counter by default.** Log for billing accuracy, token bucket when bursts are a feature.
- **Atomic read-modify-write.** `INCR` and `EXPIRE` in one script — never a separate check-then-set.
- **Fail-open with an in-process backstop.** Fail-closed only for billing-critical limits.
- **Hybrid at scale.** Local counters with periodic sync or token leases — bounded over-allowance for microsecond decisions.

## Quiz

**Distributed Rate Limiter Quiz widget** ("Hide All" / "Reveal All" toggle) — 7 questions, each with a "Show/Hide Answer" button. Full text of every question and its revealed answer:

**1) Why does a per-server in-process counter fail behind a load balancer?**
Each server sees only the fraction of traffic routed to it and counts only its own share. With a 100-per-second limit and two servers, each sees about 100 per second, each believes it is at the limit, and together they allow roughly 200 — the limit silently multiplies by the number of servers. The fix is a shared counter every limiter instance reads and writes.

**2) Why must the read-modify-write be a single atomic operation?**
If two limiter instances read the same count before either writes back, both can decide they are under the limit and both allow — a race that lets the limit drift upward under concurrency. Running increment-and-check as one atomic script (for example `INCR` plus `EXPIRE` together) serializes the decision so only one outcome is possible per request. A two-command check-then-set reintroduces the race.

**3) What is the boundary burst problem, and which algorithm avoids it?**
A fixed window counts only the current interval, so a client can send a full limit's worth at the end of one window and again at the start of the next — double the rate across the boundary while violating no single window. A sliding window counter blends the previous window's count by how far into the current window the request lands, estimating the true recent rate and denying the burst. It costs O(1) memory for a small, well-characterized error.

**4) What does a hybrid (local + periodic sync) counter trade away, and how much?**
It trades exactness for microsecond local decisions. During each sync gap, every instance can independently believe it is under the limit, so the worst-case over-allowance is roughly fleet size × per-instance rate × sync interval. With 10 instances at 1,000 req/s syncing every 100ms, that is about 1,000 extra requests in one window before the next sync corrects it — fine for soft limits, not for billing.

**5) Why do most production limiters fail open, and how do you make that safe?**
A limiter's own outage is more common than a malicious spike that happens to land during one, so failing open keeps the API serving rather than turning a limiter blip into an API outage. It is made safe with a coarse in-process backstop at the gateway — a per-IP token bucket at several times the normal rate — that needs no network and stops the worst abuse, while a distributed-tier error falls through and a distributed-tier deny still returns a 429.

**6) Why return Retry-After with jitter instead of a fixed delay on a 429?**
A denied client that retries immediately turns one over-limit request into a storm, and a single fixed delay re-synchronizes every denied client into the next instant, recreating the spike. A base delay plus small random jitter spreads the retries out over time so the backend sees a smooth trickle. Clients that ignore the header are tracked as repeat offenders and given harder backoff.

**7) What changes for a billing-critical limit where overage costs money?**
Three things move together: the algorithm becomes a sliding window log for exact counts, the counter stays central-exact rather than hybrid so there is no over-allowance window, and the failure mode flips to fail-closed because allowing uncapped billable traffic is worse than rejecting. Requests are also deduped on an idempotency key before the limit check so a retry cannot consume quota twice.

## Sources and further reading

- [How we built rate limiting — Cloudflare](https://blog.cloudflare.com/counting-things-a-lot-of-different-things/) — presents the sliding-window-counter algorithm and its ~0.003% measured error across 400M requests.
- [EVAL / Lua scripting — Redis docs](https://redis.io/docs/latest/develop/interact/programmability/eval-intro/) — how scripts execute atomically and serially on an instance, the read-modify-write primitive for counters.
- [Redis benchmarks — Redis docs](https://redis.io/docs/latest/operate/oss_and_stack/management/optimization/benchmarks/) — the ~100K ops/sec single-instance throughput ceiling that forces sharding at scale.

---
