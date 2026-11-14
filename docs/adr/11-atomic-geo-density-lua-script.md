# ADR-11: Atomic Redis Geo-Index with Per-Member TTL

**Status:** Accepted

**Date:** June 2026

## Context

The Geo-Scoring service evaluates each enrollment by executing a multi-radius density check using GEOSEARCH against a per-country Redis geospatial sorted set, followed by a GEOADD to index the point.
Three problems need to be solved:
* Concurrent density checks cause a race condition. A burst of simultaneous requests can all read an empty index and score LOW.
* Redis GEO sets lack native per-member expiry. The required rolling 48-hour window cannot be enforced directly.
* Delivery is at-least-once. A listener retry after a failed result publish, a command re-dispatch by the decision engine, and a DLQ replay each run the density check again for an enrollment that is already indexed. A second run that searches again counts the enrollment's own point, and one that indexes under a new member inflates the density for every later enrollment at that address.

## Decision

Use one integrated mechanism. A single atomic Lua script performs the density check and the index write together, a second atomic Lua script removes expired members, with a scheduled job driving cleanup. Threshold and radius configurations remain in Java; tuning these values will not require updates to the Lua scripts.

The density script is idempotent per enrollment. The index member is the `enrollmentId`. The script stores the neighbor counts of an enrollment's first run in a hash per country and, on a repeated request with the same radii, returns those counts without searching or writing. The cleanup script removes the stored counts together with the member.

## Reasoning

Redis executes a Lua script atomically on its single execution thread: the script runs to completion without interleaving commands from other clients. This atomicity provides three guarantees:
* A request's GEOSEARCH and GEOADD cannot be interleaved with another client's operations, which prevents a score based on stale data.
* The three GEOSEARCH calls (100 m, 250 m, 500 m) read the same point-in-time snapshot of the index, which prevents read skew or phantom additions between radii.
* The lookup of an earlier result and the index write are one step, so two concurrent deliveries of the same enrollment are forced into sequence: the first writes, the second reads what the first stored.

The idempotency guard is placed in the script because that is the only place where the check and the effect it protects cannot be separated. The alternatives were rejected:
* A lookup in Java before the script call leaves a gap between check and write; two deliveries on two consumers can both pass it.
* An in-memory record of handled enrollments in the listener is lost on restart and is not shared between instances, so it does not cover a DLQ replay that happens hours after the first run.
* Using the `enrollmentId` as member without storing a result keeps the index at one point per enrollment, but a repeated run still counts that point and every enrollment that arrived after the first run, so its answer differs from the first.

## Consequences

Native per-member TTL on GEO sets is not available, so it is approximated with a companion ZSET and out-of-band cleanup. The mechanism adds two extra keys per partition, the companion ZSET and the counts hash (roughly tripling the number of entries per enrollment), two Lua scripts and a scheduled job to maintain, and sub-millisecond blocking on the Redis single thread during each atomic invocation.

Redis Cluster users must ensure the {countryCode} hash tag keeps each partition and its two companions on the same slot — the key strategy enforces this, but it is a constraint on any future keying change.

The idempotency rule is implemented in Lua, not in Java. Unit tests mock the script, so only the integration tests against a real Redis prove it.

The stored result is the neighbor counts, not the risk level. Thresholds are applied in Java on every call, so a threshold change between the first run and a repeat changes the derived level. The stored counts are valid for the radii they were computed with; a request with other radii is counted again.

The stored counts expire only through the cleanup job, as the index members do. A stalled job retains both.

A Redis script is isolated from other clients but is not rolled back on error. The three writes are the last commands of the density script; a failure between them leaves the point indexed without stored counts, and a retry then counts that point.
