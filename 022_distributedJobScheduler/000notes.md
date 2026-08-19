# Design a Distributed Job Scheduler

Source: https://systemdesignschool.io/problems/job-scheduler/solution

> Note on fidelity: this page is built from many JS-interactive widgets (sliders, step-through diagrams, tabbed panels, animated simulations, expandable "Request & response" and quiz-answer panels) rather than static images. Every widget's full content — including states behind tabs/toggles/expanders, and the labels/boxes/arrows inside each diagram — has been clicked through and transcribed below as text, in the same order it appears on the site. The site has no downloadable diagram image files (they're rendered live by JS/SVG, not `<img>` files), so there are no image assets to save for this page. Note: `web_fetch` initially returned an older/alternate render of this page (a different template with a "Pro Member Exclusive" paywall cutting off later deep-dive sections); the live browser render used for this transcription is the site's current, complete, free "problem/solution" template matching the other pages in this set — no content on the live page was gated.

Tags: **Hard** · Async processing · Availability · Rate limiting

---

## Problem statement

Design a system that runs jobs at a chosen time: "run job X at time T" once, or on a recurring schedule like "every day at 09:00." The work must run reliably at scale, even when the machines running it crash, and it must run effectively once — never silently skipped, never harmfully duplicated.

In scope: submitting one-off and recurring jobs, dispatching due jobs to workers, crash recovery, and effectively-once execution. Out of scope: the workers' own job logic, and dependencies between jobs (a workflow/DAG engine, covered briefly as a variant).

## Clarifying questions

- **One-off, recurring, or both?** Both — a fixed future time, and a repeating cron expression.
- **Who runs the actual job?** A pool of workers; the dispatcher that decides when is in scope, the job's own logic is not.
- **What delivery guarantee?** At-least-once delivery with an idempotent effect, not genuine exactly-once.
- **How precise must "at time T" be?** A seconds-level latency budget — jobs firing a little late is acceptable.
- **Dependencies between jobs?** Deferred to a workflow/DAG engine layered on top, not this design.
- **What sizes the system?** The peak dispatch rate at a hot instant, not the average rate across a day.

## What makes this problem distinctive

A cron daemon that reads a table every minute and runs what's due sounds complete, until two things happen at once: a large share of a system's recurring jobs are set for midnight, and the machine running the daemon crashes mid-job. The daily average dispatch rate is a small number. The peak — every midnight job trying to fire in the same handful of seconds — can be orders of magnitude higher, and it's the number the design actually has to survive.

The second problem compounds it: once dispatch is spread across many machines for scale, a crashed dispatcher can leave a job marked "running" forever, with nothing to notice or recover it — unless the system tracks not just that a job is running, but who claimed it and when that claim expires.

![What actually sizes this system?](diagrams/what-sizes-system.svg)

"A huge share of jobs due at the same instant" → dispatch must survive it; "A dispatcher crashes mid-job" → must recover, not duplicate — both labelled "What actually sizes this system?"

> **Key idea.** The daily average dispatch rate is easy; the midnight herd is what actually sizes the system, and a crashed dispatcher must be detected and recovered from without ever running a job twice by surprise.

## Key concepts

This section covers the concepts needed to solve this problem — prerequisites for the design work that follows.

### Time-bucketed dispatch

Scanning every stored job on every tick to find what's due doesn't scale past a small number of jobs. Bucketing jobs by their due time — one bucket per minute, say — means a dispatcher reads the current bucket plus a short lookback of past-due buckets, a tiny slice of the total store, regardless of how many jobs exist overall. The lookback is what catches jobs left behind by downtime or throttling. This is the same idea as releasing a web crawler's per-host queue when its crawl-delay elapses, applied to time instead of a host.

![Time-bucketed dispatch](diagrams/time-bucketed-dispatch.svg)

Billions of jobs, bucketed by run_at minute → current minute's bucket → due jobs — a tiny slice.

### The lease

A lease is a time-bounded, atomic claim on a job: a dispatcher sets itself as owner and sets a `lease_expiry` a short window ahead, but only if the job is currently unclaimed or its previous lease already expired. A healthy owner keeps renewing the lease with a heartbeat; a crashed one stops renewing, and once the lease expires, any other dispatcher can claim the job and try again. This is the identical idea behind a message queue's visibility timeout — a message hides in flight and reappears if never acknowledged in time.

![The lease](diagrams/the-lease.svg)

Job: due → (atomic claim, if unclaimed) → owner = A, lease_expiry = +N → (renewed by heartbeat) → A crashes, no renewal, lease expires.

### Effectively-once, not exactly-once

A crash can happen in the gap between a worker finishing a job's effect and recording that it finished — no lease duration eliminates that window. Genuine exactly-once delivery isn't achievable here; what's achievable is at-least-once delivery plus an idempotent effect, the same dedup pattern used in the payment system design: the job carries the caller's idempotency key, and a repeat execution checks whether that key is already recorded before doing anything, turning a duplicate delivery into a safe no-op.

![Effectively-once, not exactly-once](diagrams/effectively-once-decision.svg)

job redelivered → idempotency_key already recorded? → no: apply effect, record key / yes: no-op.

> **Key idea.** Time-bucketing makes "what's due right now" a cheap, bounded read; a lease is what makes crash recovery possible without a dispatcher losing track of who owns what; and effectively-once is engineered from at-least-once delivery plus an idempotent effect, since true exactly-once delivery isn't on the table.

## 1. Requirements

> **Before reading on.** Name three functional and three non-functional requirements, then name the one property you would never compromise.

### 1.1 Functional requirements

- Submit a one-off job to run at a future time.
- Submit a recurring job with a cron expression that fires repeatedly.
- Dispatch each due job to a worker for execution, effectively once.
- Cancel or query a job or schedule — status, next run, attempt count.

### 1.2 Non-functional requirements

- **Durability** across crashes or restarts — a submitted job is never lost.
- **Effectively-once execution**, via at-least-once delivery plus idempotency.
- **Timely, scalable dispatch**, within a seconds-level latency budget.
- **Automatic crash recovery** for a dead dispatcher, with no manual intervention.
- **Graceful degradation** during a hot timestamp, rather than an outage.

### 1.3 The constraint versus the property

The property never to compromise is **effectively-once execution**: a job never silently skipped, and never harmfully duplicated. The constraint that drives the design is the gap between the daily average dispatch rate and the peak at a hot instant — often hundreds of times larger — which is why the architecture is built around surviving that peak, not the average.

> **Key idea.** Effectively-once execution is the property that can't bend; surviving the peak-versus-average gap at a hot instant is the constraint the rest of the design answers.

## 2. Back-of-the-envelope estimation

**Interactive estimation widget (default values shown):**

| Metric | Value | Note |
|---|---|---|
| Jobs executed / day | 1.0B | |
| Share due at midnight | 18% | |
| Midnight firing window | 60s | |
| Average dispatch rate | 12K/s | spread evenly across the day |
| Midnight bucket peak rate | 3.0M/s | 180M jobs in 60s |
| Peak vs. average | 259× | the ratio the design is actually sized for |
| What this means | size for the herd | not the daily average |

Formula shown: `peak = 18% of 1.0B jobs ÷ 60s ≈ 3.0M/s — 259× the daily average`

The daily average is small and easy. The midnight bucket — a large share of recurring jobs all due at once — is what the dispatch path must actually survive.

### 2.1 The daily average is easy

Assume roughly 1 billion job executions a day: `1,000,000,000 ÷ 86,400 ≈ 12,000` jobs a second, spread evenly. Storage for a billion pending jobs at roughly 1 KB each is about 1 TB — unremarkable.

### 2.2 The midnight bucket is the real number

Assume roughly 180 million daily cron jobs are all scheduled for midnight, firing within one minute: `180,000,000 ÷ 60 = 3,000,000` jobs a second at that instant.

### 2.3 Peak, not average, sizes the design

`3,000,000 ÷ 12,000 ≈ 250` — the midnight peak runs roughly 250 times the daily average. Provisioning for the average and hoping the peak is manageable is exactly backwards.

> **Key idea.** The daily average (thousands a second) is a rounding error next to the midnight peak (millions a second) — the peak-to-average ratio is the number that actually drives the design.

## 3. API design

**Design checkpoint widget:** *"A one-off job and a recurring job need different fields to describe 'when.' Should the API expose two separate endpoints, or one endpoint with a field that varies by job type?"* Options: (a) *Two separate endpoints, POST /jobs and POST /schedules*; (b) *One endpoint where the request carries either run_at or a cron expression* — the design picks (b).

### 3.1 Submit a job

`POST /v1/jobs`

**Request & response (expanded):**
- Request body: `{ idempotency_key, run_at | cron, payload, max_retries? }`
- Response body: `{ job_id }`

A one-off job carries `run_at`; a recurring job carries `cron` instead. Everything else exists so a job that runs later — possibly repeatedly — can be tracked, retried safely, and cancelled.

### 3.2 Check job status

`GET /v1/jobs/{id}`

**Request & response (expanded):**
- Response body: `{ status, next_run, attempts, last_error }`

### 3.3 Cancel a job or schedule

`DELETE /v1/jobs/{id}`

**Request & response (expanded):**
- Response body: `200 OK`

Cancels a future one-off job outright, or pauses a recurring schedule.

> **Key idea.** One field — `run_at` or `cron` — carries almost all the weight of the API; everything else exists to make a delayed, possibly-repeating job trackable, retryable, and cancellable.

## 4. Data model

### 4.1 Job

A one-off unit of scheduled work, with the lease and idempotency machinery folded on.

- `string job_id`
- `string payload`
- `timestamp run_at`
- `enum status`
- `string owner_id`
- `timestamp lease_expiry`
- `int attempt_count`
- `string idempotency_key`

A recurring job can't be represented as one fixed-time row — "every day at 09:00" has no single `run_at`, it fires forever. The repeating definition is a different thing from any one occurrence.

### 4.2 Schedule

The recurring definition, separate from any single fire.

- `string schedule_id`
- `string cron_expr`
- `timestamp next_run`
- `string payload`
- `enum status`

When due, a Schedule spawns a concrete Job instance for that occurrence and advances `next_run` — the deep dive on recurring schedules covers this in full.

### 4.3 Where each entity lives

![Schedule and Job: entity relationship](diagrams/er-schedule-job.svg)

ER-style: Schedule (1) —* Job (*) — Schedule holds schedule_id, cron_expr, next_run; Job holds job_id, run_at, status, owner_id.

Job and Schedule live in a durable, authoritative store — the source of truth, written on submit and on completion. A separate, read-optimized due index, partitioned by `run_at`, is what dispatchers actually poll — submit-writes and due-reads have opposite access patterns, so they get different physical structures even though they describe the same jobs.

> **Key idea.** A job can't hold a recurring definition, which forces a separate Schedule entity; a job can't safely track "who's running this," which forces the lease fields onto Job; and the store's write pattern (by job_id) differs enough from the dispatch read pattern (by due time) to warrant a separate due index.

## 5. High-level design

> **Before reading on.** You already have time-bucketed dispatch, the lease, and effectively-once execution from Key concepts. Sketch what happens from "a job's time arrives" to "it runs exactly the right number of times," and where each mechanism plugs in.

*Reading the diagrams: each step marks the components newly added at that step with a dashed outline and a NEW badge.*

### 5.1 One cron process, one table

Start naive: a single process reads a table every tick, finds due rows, and runs them inline.

![Step 0: one cron process, one table](diagrams/step0-naive-cron.svg)

Cron process → (scan every tick) → Job table → Due job → (run inline).

Five things break this.

- One process is a single point of failure and can't execute millions of jobs at the peak.
- Scanning a billion-row table every tick is ruinous, even when almost nothing is due.
- Nothing stops two dispatchers (once there are more than one) from running the same due job at once.
- A huge share of jobs firing in the same instant is a self-inflicted denial-of-service on the workers and whatever they call downstream.
- A job that fails gets no retry, no backoff, and no way to stop hammering something that's permanently broken.

### 5.2 Fix 1: a durable store and several stateless dispatchers

Move state out of any single process into a durable store, and run many stateless dispatchers against it.

![Fix 1: a durable store and stateless dispatchers](diagrams/fix1-durable-store.svg)

Dispatcher 1, Dispatcher 2 → Durable job store (NEW).

The dispatcher tier is no longer a single point of failure — the durable store itself must be replicated so it doesn't become the new one. Every dispatcher still scans the whole table on every tick.

### 5.3 Fix 2: a time-partitioned due index

Bucket jobs by due time — one bucket per minute — so a dispatcher reads only the current bucket plus a short past-due lookback.

![Fix 2: a time-partitioned due index](diagrams/fix2-time-partitioned-index.svg)

Job store → Due index, bucketed by minute (NEW) → Dispatcher (read current bucket + lookback).

Dispatch reads now scale with what's due, not with total job count — though a single hot bucket can still be huge, which the deep dive shards further. Two dispatchers can still race on the same due job.

### 5.4 Fix 3: an atomic lease claim

A dispatcher claims a job with an atomic conditional update — set owner and `lease_expiry`, only if the job is currently unclaimed or its previous lease has expired.

![Fix 3: an atomic lease claim](diagrams/fix3-atomic-lease-claim.svg)

Dispatcher 1 → "unclaimed?" (NEW) → first: yes → owner = D1, lease set; Dispatcher 2 → second: no → skip, already claimed.

At most one dispatcher holds a valid lease at a time — though a paused or partitioned owner can still be mid-execution when its lease lapses, which is why the effect itself must stay idempotent (deep dive 6.3). A huge bucket of jobs due at once still fires all at once.

### 5.5 Fix 4: jitter and rate-limited dispatch

Spread each job's effective fire time with a small random jitter across a window, and cap total dispatch with a rate limiter.

![Fix 4: jitter and rate-limited dispatch](diagrams/fix4-jitter-rate-limit.svg)

Midnight bucket → jitter fire time across a window (NEW) → rate limiter (NEW) → Worker pool.

### 5.6 Fix 5: retries, backoff, and a dead-letter queue

A job that fails transiently retries with exponential backoff; one that keeps failing past a capped attempt count goes to a dead-letter queue instead of retrying forever.

![Fix 5: retries, backoff, and a dead-letter queue](diagrams/fix5-retries-dlq.svg)

Worker → fails → "attempts < max?" → yes: retry, exponential backoff / no: Dead-letter queue.

### 5.7 The composed design

![The composed design](diagrams/step6-composed-design.svg)

Full composed design: Submit API → Job + Schedule store → Due index (time-bucketed) → Dispatchers: lease claim, jitter + rate limit → Work queue → Worker pool → (success / fail) → Dead-letter queue.

Each fix answers one failure of the naive cron process: the durable store and stateless dispatchers fix the single point of failure, the time-bucketed index fixes scan cost, the atomic lease fixes double-claiming, jitter and rate limiting fix the herd, and retries with a dead-letter queue fix permanent failures.

> **Key idea.** Every component traces to one concrete failure of "a cron process and a table" — single point of failure, scan cost, double-claiming, the herd, and unbounded retries — not a pre-known architecture diagram.

## 6. Deep dives

### 6.1 Time-based dispatch and the thundering herd

> **Before reading on.** A fifth of the day's jobs are all set for 00:00. Two separate problems hide in that sentence: finding them cheaply, and firing them without knocking over everything downstream. Solve both.

Finding due jobs cheaply is the time-bucketing from Key concepts: partition time into fixed intervals — one bucket per minute — so a dispatcher's read cost is proportional to jobs due right now, not to the total number of jobs ever stored. A single bucket can still be enormous, so it's sharded further by `(time_bucket, hash(job_id))`, spreading even a massive bucket across many shards and dispatchers.

Firing them without a stampede is a separate problem the bucketing alone doesn't solve. Adding randomized jitter to each job's fire moment, spread across a window after its nominal due time, turns a single-instant spike into a smoothed-out stream. Jitter alone still isn't enough: a large enough bucket exceeds what downstream systems can absorb even after being spread across a window. A rate limiter caps total dispatch directly, so the stream drains at a survivable rate. Jobs fire slightly delayed by design — which the seconds-level latency budget from the requirements explicitly permits.

![Herd mitigation: shard, jitter, rate-limit](diagrams/herd-mitigation-chain.svg)

Midnight bucket (huge share of daily jobs) → shard by (time_bucket, hash(job_id)) → jitter fire time across a window → rate limiter caps drain rate → Worker pool.

**"What separates answers — time-based dispatch and the thundering herd" (expandable rating list):**
- **Bad** — Scan the whole table; fire everything at once
- **Good** — Time-bucketed due index, jobs pushed to a queue
- **Great** — Shard the hot bucket, jitter fire times, rate-limit dispatch

### 6.2 Crash recovery with leases

> **Before reading on.** A dispatcher claims a due job, starts it, and dies. The job is now marked "running" but nobody is running it. How does the system notice and recover, without risking two runs?

**Simulation widget:** tabs "Dispatcher A crashes at tick t=3"; controls "Play" / "Reset"; state readout "t = 0"; two rows Dispatcher A (idle), Dispatcher B (idle); readout "lease time remaining", "effect applied: not yet"; status line "Job is unclaimed, scheduled_at has passed."

The claim itself is an atomic conditional write: set `owner_id` and `lease_expiry` a short duration ahead, only if the job is currently unclaimed or its lease has already expired — exactly one dispatcher wins, the same semantics as a conditional SET with expiry. A healthy owner renews its lease periodically via heartbeat; a crashed owner stops renewing, the lease expires on schedule, and another dispatcher reclaims the job. This mirrors a message queue's visibility timeout: a message hides from other consumers while in flight and reappears automatically if never acknowledged.

**Design checkpoint widget:** *"Making the lease duration very long would seem to reduce false reclaims from slow-but-healthy workers. What does a long lease duration cost instead?"* Options: (a) *Nothing — longer is strictly safer*; (b) *A genuinely crashed job stays undetected and unrecovered for the entire lease duration* — the design picks (b).

Lease duration is a real tradeoff. Too short, and a healthy-but-slow worker can lose its lease mid-job, triggering a second execution of a job that was actually fine. Too long, and a genuinely crashed job stays undetected — and unrecovered — until the lease finally expires. The practical answer is a duration slightly longer than typical run time, with renewal for jobs that legitimately run long.

Even a well-tuned lease has one unavoidable gap: a worker can finish a job's effect and then crash before acknowledging completion. The lease then expires exactly as if the worker had never started, and another dispatcher reclaims and re-executes a job that already ran. No lease duration eliminates this window — which is precisely why the effect itself has to be idempotent, not just the delivery.

**"What separates answers — crash recovery with leases" (expandable rating list):**
- **Bad** — A boolean 'running' flag, assume success
- **Good** — An atomic lease with owner and expiry
- **Great** — Heartbeat renewal, duration calibrated to run time, idempotency for the finish-then-crash gap

### 6.3 Effectively-once execution

> **Before reading on.** Why is genuine exactly-once impossible here, and what pair of mechanisms gives you the effect you actually want?

Exactly-once delivery isn't achievable across independent machines: there's always a window between performing an effect and durably recording that it happened, and a crash in that window is indistinguishable from a crash before the effect ran at all. What's achievable instead is at-least-once delivery plus an idempotent effect, which together produce an exactly-once outcome even though delivery itself may repeat.

The job carries the caller's idempotency key; before applying the effect, the worker checks whether that key is already recorded as done, the identical dedup move covered in the payment system design. That check-and-record step has to be atomic — if checking and recording aren't one indivisible operation, two concurrent redeliveries can both pass the check before either records the key, applying the effect twice anyway.

![Concurrent redeliveries, idempotency key](diagrams/redelivery-idempotency.svg)

Redelivery 1 (first to arrive) → atomic check-and-record on idempotency_key → apply effect, record key; Redelivery 2, concurrent (second, key now recorded) → no-op.

The retry machinery itself — a queue, exponential jittered backoff, and a dead-letter queue for jobs that exceed a capped attempt count — is the same async-processing backbone used throughout distributed systems, not something rebuilt specifically for scheduling.

**"What separates answers — effectively-once execution" (expandable rating list):**
- **Bad** — Assume the queue delivers exactly once
- **Good** — At-least-once with an idempotency key, retries with backoff
- **Great** — States exactly-once is impossible, engineers the effect, atomic check-and-record, jittered backoff, DLQ, monitored duplicate rate

### 6.4 Recurring schedules and catch-up

> **Before reading on.** The scheduler was down across a fire time. What's the right default, and what must you prevent when a run outlasts its next fire?

A Schedule holds the cron expression and `next_run`; when due, the scheduler spawns a concrete Job instance for that occurrence and computes the following `next_run`. That spawn step must itself be idempotent per `(schedule, fire-time)` pair — otherwise a dispatcher reclaiming a schedule after a crash could spawn a duplicate job instance for the same occurrence.

Missed fires have no single correct default; the right policy genuinely depends on the job. Skip discards stale fires — appropriate for something like a health check, which only cares about current state, not a backlog of past ones. Catch-up runs every missed period — appropriate for something like billing, where every period genuinely needs to execute regardless of how late. Exposing this as a per-schedule policy, rather than picking one default for everything, is what makes both cases correct.

**Comparison widget (two side-by-side scenarios, "3 fires missed during downtime"):**
- *Catch-up — replay every missed fire:* all 3 run, in order.
- *Skip — discard stale fires:* all 3 discarded, resume from now.

A separate hazard: if one occurrence's run outlasts the time its next occurrence is due, running both concurrently can corrupt whatever shared state the job touches. A per-schedule lock — only one active run of a given schedule at a time — prevents that overlap. Finally, daylight-saving transitions need the schedule's intended time zone, not just a UTC instant. Store the cron expression together with its IANA time zone, compute each next occurrence in that zone — handling local times that repeat or don't exist during a transition — and convert to UTC only as the derived dispatch instant. Storing a fixed UTC time alone would silently shift a "9:00 local" schedule by an hour twice a year.

**"What separates answers — recurring schedules and catch-up" (expandable rating list):**
- **Bad** — One fixed run_at reused; missed runs vanish; overlaps allowed
- **Good** — Schedule computes next_run and spawns instances; missed fires have some policy
- **Great** — Idempotent spawn, per-schedule missed-fire policy, overlap lock, time-zone-aware cron

> **Key idea.** Sharding the hot bucket plus jitter and rate limiting is what turns a synchronized stampede into a survivable stream; a lease with heartbeat renewal recovers from a crash, but only idempotency closes the finish-then-crash gap no lease duration can; and a recurring schedule needs idempotent spawning, a per-schedule missed-fire policy, and a time-zone-aware cron definition to stay correct across restarts and daylight-saving transitions.

## 7. Variants

### 10× scale

The architecture doesn't change shape — the durable store and due index shard further, the hot bucket spreads across more nodes, and the rate limiter and worker pool scale with peak capacity. Jitter widens to spread the herd over a longer window as its absolute size grows. The line item that grows fastest is dispatch at the hot instant; the daily average barely moves, so provisioning for the peak and filling the troughs with non-urgent, deferrable work keeps that capacity from sitting idle the rest of the day.

### Higher-precision scheduling

Sub-second precision needs finer buckets than one per minute — a timing wheel, a ring of small time slots that a clock hand advances through with due jobs attached to each slot — at millisecond resolution, with dispatchers polling far more aggressively. The tradeoff cuts directly against the herd mitigation from the deep dives: tighter timing tolerances leave much less room for jitter, making the thundering-herd problem correspondingly harder to smooth out.

### Workflow dependencies

Once jobs gain dependencies — run B only after A succeeds, with a defined compensation if a later step fails — the problem has crossed from scheduling into a workflow or DAG engine. A saga is the right tool for that ordering and compensation logic; this scheduler still fires the timed triggers underneath it, but ownership of ordering and rollback belongs to the workflow layer, not here.

> **Key idea.** The architecture holds at 10× scale by sharding further and provisioning for the peak; higher precision directly trades away jitter room, making the herd problem harder; and job dependencies are a workflow engine's problem, layered on top of — not inside — this scheduler.

## 8. The transferable pattern

A distributed job scheduler is a durable store of future work plus a time-indexed dispatcher that leases each item to a worker and makes the effect idempotent. Time-bucketing is what makes "what's due right now" cheap to find regardless of total volume; leasing is what makes crash recovery possible without losing track of ownership; idempotency keys are what make at-least-once delivery safe; jitter and rate limits are what let the design survive a traffic surge instead of amplifying it. It rhymes with a web crawler's frontier (release work when its time comes) and a payment system's idempotency (make an at-least-once effect happen once) — the same shape applies wherever deferred, single-occurrence work has to survive machine failure.

## Review: the 30-second answer

- A durable job store is the source of truth; a time-bucketed due index is what makes dispatch cheap to find.
- An atomic lease — owner plus expiry, renewed by heartbeat — is what makes crash recovery possible without double-claiming.
- At-least-once delivery plus an idempotent effect equals effectively-once execution; genuine exactly-once delivery isn't achievable.
- Jitter and a rate limiter turn a synchronized stampede (the midnight herd) into a survivable, spread-out stream.
- The midnight bucket, not the daily average, is what actually sizes this system.

## Quiz

**Quiz widget ("Job Scheduler Design Quiz")** — "Hide All" / "Reveal All" toggle — 5 questions, each with a "Show/Hide Answer" button. Full text of every question and its revealed answer:

**1) Why does scanning the entire job table on every tick fail at scale, and what fixes it?**
A full-table scan costs the same whether one job or a billion are due, so as the store grows, the cost of finding "what's due right now" grows with it even though the actual answer — a handful of due jobs — barely changes. Bucketing jobs by their due time so a dispatcher only reads the current bucket makes read cost proportional to jobs due now, not to total jobs ever stored.

**2) Why does an atomic lease claim, rather than a simple 'running' status flag, prevent two dispatchers from executing the same job?**
A status flag alone can't distinguish "legitimately still running" from "the owner crashed and this is stuck forever," so nothing can safely decide to re-run it, and a plain read-then-write status update lets two dispatchers both see the job as unclaimed before either commits its claim. An atomic conditional update only succeeds if the job is currently unclaimed or its lease already expired, so exactly one concurrent claim attempt can ever win.

**3) A lease is well-tuned — long enough to cover typical run time, short enough to recover quickly from a real crash. Why can a job still execute twice?**
A worker can finish the job's actual effect and then crash before it acknowledges completion back to the dispatcher — a gap no lease duration can close, because from the system's perspective that worker looks identical to one that crashed before doing anything. The lease expires as designed, another dispatcher reclaims the job, and re-executes it. This is exactly why the effect itself, not just the delivery mechanism, has to be idempotent.

**4) Why is 'skip' versus 'catch-up' for missed schedule fires exposed as a per-schedule policy instead of one global default?**
Different recurring jobs need genuinely different behavior after an outage: a health check only cares about current state, so replaying every missed check is wasted and possibly misleading, while a billing job that must charge every period needs every missed occurrence to eventually run, or a customer is silently undercharged. No single default is correct for both, so the policy has to be a property of the schedule itself, not a system-wide constant.

**5) Why does jitter alone (without a rate limiter) not fully solve the midnight-herd problem, and what does the rate limiter add?**
Jitter spreads each job's fire moment across a window, which smooths out the instantaneous spike into a longer stream — but without any cap, that stream's total rate is still whatever the bucket's raw size divided by the window happens to be, which can still exceed what downstream systems can absorb if the bucket is large enough. A rate limiter caps the actual dispatch rate directly, so the drain rate stays within a survivable bound regardless of how large any single bucket turns out to be.

## Sources and further reading

- Distributed locks with Redis — Redis docs — the SET if-not-exists-plus-expiry mechanism behind a time-bounded job lease.
- Exactly-once semantics in Kafka — Conduktor — why cross-machine delivery is fundamentally at-least-once, and how idempotency turns it into an effectively-once outcome.

