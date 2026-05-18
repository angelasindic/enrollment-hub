# ADR-004: 48-Hour TTL as Architectural Asset

**Status:** Accepted  
**Date:** May 2026

## Context

Dense legitimate housing (apartment buildings, office complexes) naturally generates high-density clusters of accounts, potentially indistinguishable from fraud rings and producing false positives in urban areas.

## Decision

Retain the 48-hour TTL on all cached data in Redis. Document it as a feature, not just a storage constraint.

The 48-hour window naturally mitigates the apartment building false-positive problem. A 200-unit building only triggers a density alert if a substantial fraction of units create accounts within the *same 48-hour window* — which is a legitimately suspicious signal even for real apartments. Without the TTL, clusters would accumulate over months, making it impossible to distinguish a building that gained 50 accounts over a year from a fraud ring that created 50 accounts in a day.

At ≤100,000 points in the cache at peak, the dataset remains small enough that all density checks are computationally trivial.

## Consequences

**Gains:** Natural false-positive mitigation for dense housing. GDPR data minimization — data is "processed in transit" with automatic expiration. Bounded cache size.

**Loses:** No historical density analysis beyond 48 hours in real-time. Mitigated by Phase 2 Batch Analytics which can operate on longer time windows if needed.

> **Implementation note:** Redis geo sorted sets do not support per-member expiry natively; a key-level TTL would expire 
> an entire country partition simultaneously. Per-member TTL is enforced via a companion sorted set scored by insertion 
> epoch plus a scheduled batched Lua cleanup job — see **ADR-014**. The 48h window above is therefore enforced at 
> the member level, with worst-case slack bounded by the cleanup interval (default 1h).
