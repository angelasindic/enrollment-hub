# ADR-12: 48-Hour TTL as Architectural Asset

**Status:** Accepted  
**Date:** June 2026

## Context

The geo-index must retain enough history to detect coordinated enrollment bursts, yet cannot hold spatial data indefinitely. GDPR requires automatic, irreversible expiration of high-precision location data (architecture §2.2).

## Decision

Retain the 48-hour TTL on all cached data in Redis. Document it as a feature, not just a storage constraint.

The 48-hour window naturally mitigates the apartment building false-positive problem. A 200-unit building only triggers a density alert if a substantial fraction of units create accounts within the *same 48-hour window* — which is a legitimately suspicious signal even for real apartments. Without the TTL, clusters would accumulate over months, making it impossible to distinguish a building that gained 50 accounts over a year from a fraud ring that created 50 accounts in a day.

At ≤100,000 points in the cache at peak, the dataset remains small enough that all density checks are computationally trivial.

## Consequences

**Gains:** Natural false-positive mitigation for dense housing. GDPR data minimization and bounded cache size.

**Loses:** No historical density analysis beyond 48 hours in real-time. 

