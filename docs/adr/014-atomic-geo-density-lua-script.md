# ADR-014: Atomic Redis Geo-Index with Per-Member TTL

**Status:** Accepted
**Date:** May 2026

## Context

The geo-scoring service maintains a Redis GEO sorted set per country partition
(`geo:{countryCode}`) and, for each incoming account, performs a density check
(GEOSEARCH at multiple radii) followed by indexing (GEOADD). Two coupled problems
must be solved together, because the chosen mechanism for one constrains the other.

### Problem 1 — Concurrency races on the density check

Executed as separate Redis commands, the density check has two distinct races:

1. **Intra-request TOCTOU:** The three GEOSEARCH queries execute sequentially, so
   concurrent GEOADD operations from other requests can mutate the index between the
   100m and 500m reads. Sub-millisecond window, 1–2 account discrepancy — benign, but
   undesirable.

2. **Inter-request concurrent race (F-3):** If a fraud ring submits N accounts
   simultaneously, each worker performs GEOSEARCH (sees count = 0) before any
   worker's GEOADD completes. All N score LOW. The score-before-index ordering only
   holds when requests are sequential. An adversarial actor aware of this property can
   time a burst to exploit it.

F-3 is the more severe issue — it is a deliberate bypass vector, not a benign timing
artifact, and contradicts the unconditional-indexing design intent (§5.1.2) which is
specifically built to make iterative probing self-defeating.

### Problem 2 — Per-member TTL on the geo-index

ADR-004 establishes a 48-hour window on the geo-index as a deliberate architectural
property: it bounds the cache, enforces GDPR data minimization ("processed in
transit"), and naturally mitigates the apartment-building false-positive problem.
However, Redis GEO sorted sets do not support per-member expiry natively —
`EXPIRE`/`PEXPIRE` operate at the key level only. A key-level TTL would expire an
entire country partition simultaneously, destroying the rolling window the design
depends on.

### Why these problems must be decided together

The per-member TTL mechanism must not break the single-key atomicity that solves the
concurrency races. Any TTL scheme that fragments the geo-index across multiple keys
(e.g. time buckets) forces GEOSEARCH to union across keys, which Redis does not
natively offer — and even if emulated in Lua, it loses the F-3 guarantee this ADR
relies on.

## Options considered

### Atomicity mechanism

| Option | Pros | Cons |
|---|---|---|
| **Lua script (EVAL)** | Atomic — Redis single-threaded execution eliminates both races. No external infrastructure. Same-key guarantee works in Redis Cluster. | Lua scripts block other commands during execution (acceptable at sub-millisecond duration). |
| **Redis lock (SETNX) per partition** | Familiar distributed lock pattern. | Adds SETNX/retry/timeout complexity. Lock contention under burst load. Failure modes (lock expiry, deadlock) require careful handling. |
| **Reverse to index-before-score** | Simple ordering change. | Breaks crash recovery property (retry after failure double-counts). Requires threshold adjustment (+1 self-count). Changes semantic question from "how many were here before me" to "how many are here including me". |
| **Accept the risk** | Zero implementation effort. | Leaves a known, exploitable bypass vector. Contradicts unconditional-indexing design intent. |

### Per-member TTL mechanism

| Option | Pros | Cons |
|---|---|---|
| **Companion sorted set + scheduled cleanup** | Single-key atomicity for the hot path preserved. Cleanup runs out of band at predictable cadence. Per-member TTL granularity at whatever cadence the job runs. | Adds one background job and one additional key per partition. Expiry is lazy — members can outlive `ttl` by up to one cleanup interval. |
| **Read-side timestamp filtering** | No background job. Simplest to reason about. | Every GEOSEARCH must post-filter by timestamp, requiring extra round trips or a second data structure read inside the Lua script. Unbounded growth of the underlying set between reads. |
| **Time-bucketed keys** (one GEO key per hour, union at query time) | Key-level TTL does the expiry for free. | GEOSEARCH across N bucket keys requires either N separate searches and client-side merge, or a union primitive Redis does not offer for GEO. **Breaks the single-key atomicity the F-3 fix depends on.** Complicates the Lua script. |

## Decision

A single integrated mechanism — **one atomic Lua script for writes, one for cleanup,
plus a scheduled cleanup job.**

### Data model

Two ZSETs per country partition:

- `geo:{countryCode}` — Redis GEO sorted set. Member = `requestId`, score =
  geohash of `(lon, lat)` computed by `GEOADD`.
- `geo:{countryCode}:ttl` — plain sorted set. Member = same `requestId`, score =
  insertion epoch seconds. Exists solely to drive per-member expiry.

Both keys share the same hash tag (`{countryCode}` can be wrapped if Redis Cluster
is later adopted) so they route to the same slot.

### Write path — `geo-density.lua`

A single `EVAL` performs density check and dual-write atomically:

```
for each radius in [100, 250, 500]:
    count = #GEOSEARCH KEYS[1] FROMLONLAT lon lat BYRADIUS radius m ASC COUNT 200
GEOADD KEYS[1] lon lat member       -- geo-index
ZADD   KEYS[2] epoch   member       -- TTL companion
return counts
```

Threshold comparison remains in Java (`GeoIndexService`) — this keeps the Lua script
simple and means configuration changes (radii, thresholds) don't require script
updates.

**Why this eliminates both races:**
- **F-3 (inter-request):** Two concurrent workers targeting the same partition
  cannot interleave. Worker A's entire script completes before Worker B's begins.
  Worker B sees Worker A's indexed point.
- **Intra-request TOCTOU:** The three GEOSEARCH queries within a single script
  execution see the same index snapshot.

**Why the dual-write is safe:** Both writes are inside the same `EVAL`, so the GEO
index and the TTL tracker cannot diverge. Either both writes are visible to
subsequent scripts, or neither is. No additional round trip, no additional race
surface.

### Cleanup path — `geo-cleanup.lua`

A second Lua script removes expired members atomically, in batches:

```
expired = ZRANGEBYSCORE ttlKey -inf cutoff LIMIT 0 batch
if #expired > 0:
    ZREM key    <expired...>
    ZREM ttlKey <expired...>
return #expired
```

The batch limit (default 1000) keeps each script execution sub-millisecond, so the
single-threaded Redis server is not blocked for long. The caller loops until the
script returns less than the batch size.

A `@Scheduled` job (`GeoIndexCleanupJob`, `fixedDelay = PT1H` by default) uses Redis
`SCAN` to enumerate active partitions under the `geo:*` prefix (skipping `:ttl`
companions), then calls `GeoIndexService.cleanupExpired(cc, now - ttl)` for each.

### Expiry granularity and bounds

Members can outlive the configured `ttl` by up to one `cleanup-interval`. With
defaults (`ttl = 48h`, `cleanup-interval = 1h`), the worst-case effective TTL is
**49 hours** — within ADR-004's tolerance (a design window, not a hard legal
boundary). If a tighter bound is required later (e.g. a GDPR retention commitment),
reduce `cleanup-interval` without changing the data model.

### Performance impact

3× GEOSEARCH + 1× GEOADD + 1× ZADD on two keys executes sub-millisecond. At peak
load (50K accounts/day ≈ 0.6 RPS), contention is negligible. At 10× peak the
blocking window is still too short to measurably affect latency. Cleanup runs once
per hour regardless of load — moving the cost off the hot path onto the cleanup
path is the right trade.

### Redis Cluster compatibility

All commands in each script operate on keys within a single country partition.
`geo:{cc}` and `geo:{cc}:ttl` can be colocated on the same slot via hash-tag
convention (`{cc}`) if/when cluster mode is adopted — no data migration required,
since the data is ephemeral.

## Consequences

**Gains:**
- Eliminates the F-3 concurrent race — a fraud burst now produces strictly
  increasing neighbor counts (0, 1, 2, ..., N-1) instead of all-zero.
- Eliminates the intra-request TOCTOU — all radii read the same snapshot.
- The 48h window stated in ADR-004 is enforced at the member level.
- Preserves score-before-index ordering and crash recovery (retry re-runs the
  entire script idempotently).
- Cleanup cost is bounded and out of band — no impact on write-path latency.
- No external locking infrastructure required.

**Loses:**
- One extra key per active partition (ZSET of the same size as the GEO set).
  Memory overhead ~2× the GEO set at peak (≤100K points per ADR-004 → low
  hundreds of KB).
- Expiry is lazy with up to one cleanup-interval of slack.
- Two new operational artifacts: the Lua scripts (small, ~20 and ~10 lines) and
  one `@Scheduled` component (`GeoIndexCleanupJob`) to monitor.
- Script execution blocks other commands on the same Redis instance for
  sub-millisecond durations.

### Failure modes

- **Cleanup job down.** Partitions grow unbounded until the job resumes. Detect
  via trending `ZCARD geo:{cc}` / `ZCARD geo:{cc}:ttl`. No correctness impact on
  density checks — stale members inflate neighbor counts, which is conservative
  (biases toward HIGH, not LOW). On resume, one catch-up run drains the backlog
  in batches.
- **Cleanup script partial failure.** Each batch is its own atomic script. A
  failure between batches leaves the partition partially cleaned, which is safe —
  the next run picks up where the previous stopped.
- **Companion set drift.** Could diverge from the GEO set only if a write
  bypasses the Lua script. All writes go through `GeoIndexService.checkAndIndex`,
  which uses the Lua script exclusively. Direct Redis writes are not part of the
  service contract.

## Configuration & implementation

This ADR fixes the *mechanism* (atomic Lua + companion ZSET + scheduled cleanup) and the *parameter set*
that needs to be tunable (`key-prefix`, `search-limit`, `ttl`, `cleanup-interval`, `cleanup-batch-size`).
Concrete defaults, environment variables, and the Java/Lua file layout that implements the mechanism are
documented alongside the code rather than duplicated here:

- Configuration defaults and environment overrides — `geo-scoring/README.md` ("Configuration" table).
- Lua script contents, Java service responsibilities, and the cleanup-job wiring — `geo-scoring/design.md`
  (§"Steps 2 & 3 — Atomic Density Check + Index" and §"TTL companion sorted set").

## Related

- ADR-001: Fixed-radius density check — defines the multi-radius approach this script implements.
- ADR-004: 48-hour TTL as architectural asset — the window this mechanism enforces.
- ADR-005: Technology stack — Lettuce driver supports `EVAL`/`EVALSHA` natively.
