# ADR-11: Atomic Redis Geo-Index with Per-Member TTL

**Status:** Accepted

**Date:** June 2026

## Context

The Geo-Scoring service evaluates each enrollment by executing a multi-radius density check using GEOSEARCH against a per-country Redis geospatial sorted set, followed by a GEOADD to index the point.
Two problems need to be solved:
* Concurrent density checks cause a race condition. A burst of simultaneous requests can all read an empty index and score LOW
* Redis GEO sets lack native per-member expiry. The required rolling 48-hour window cannot be enforced directly.

## Decision

Use one integrated mechanism. A single atomic Lua script performs the density check and the index write together, a second atomic Lua script removes expired members, with a scheduled job driving cleanup. Threshold and radius configurations remain in Java; tuning these values will not require updates to the Lua scripts.

## Reasoning

Redis executes a Lua script atomically on its single execution thread: the script runs to completion without interleaving commands from other clients. This atomicity leveraged for:
* request's GEOSEARCH and GEOADD cannot be interleaved with another client's operations, prevents score being based on stale data.
* the three GEOSEARCH calls (100 m, 250 m, 500 m) read the same point-in-time snapshot of the index which prvents read skew or phantom additions between radii.

## Consequences

Since native per-member TTL on GEO sets is not available we approximate it with a companion ZSET and out-of-band cleanup. The mechanism adds one extra key per partition (roughly doubling memory footprint), two Lua scripts and a scheduled job to maintain, and sub-millisecond blocking on the Redis single thread during each atomic invocation.

Redis Cluster users must ensure the {countryCode} hash tag keeps each partition and its companion on the same slot — the key strategy enforces this, but it is a constraint on any future keying change.
