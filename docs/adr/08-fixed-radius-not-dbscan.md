# ADR-08: Density Detection Algorithm — Fixed-Radius GEOSEARCH

**Status:** Accepted

**Date:** June 2026

## Context

The Geo-Scoring service detects accounts clustered within a tight physical area such as one building or a short block of adjacent addresses. DBSCAN clustering and vector-embedding address matching were both considered before the fixed-radius approach was chosen.

## Decision

Detect address clustering with Redis `GEOSEARCH` fixed-radius neighbour counting. DBSCAN and vector-embedding similarity were both rejected.

## Reasoning

The operative question is how many accounts fall within a fixed radius of a given point. A fixed-radius count answers that question directly. Each evaluation is independent and runs in O(log n) against the Redis geo index, and it maps onto the multi-radius risk levels defined in the Geo-Scoring design.

DBSCAN was rejected on two grounds. Its density-reachable chaining merges points connected through intermediate cores, so two distant addresses can share a cluster. That models connectivity rather than concentration and does not match a threat confined to a tight area. DBSCAN is also a batch method that requires the full point set of a region to classify core, border, and noise membership. That property conflicts with real-time scoring, complicates maintenance as members expire under the 48h TTL (ADR-12), and adds a per-account re-run cost that fixed-radius counting does not incur.

Vector embeddings were rejected for lack of demonstrated benefit. The candidate model `all-MiniLM-L6-v2` was trained on natural-language sentences rather than short structured address strings, and its behaviour on address input is unvalidated. The proposed hybrid metric `D = α · Haversine + (1 − α) · Cosine` mixes meters with a unitless cosine score and needs normalisation that adds complexity without proven gain. The formatting variance it was meant to absorb is already handled before scoring. Libpostal normalisation (ADR-10) canonicalises component order, casing, and abbreviations, and Nominatim geocoding (ADR-09) resolves remaining variants to identical coordinates. Inputs that describe one physical address converge on a single `(lon, lat)` before `GEOSEARCH` runs, so semantic similarity contributes no additional detection surface.

## Consequences

The method scores each account in real time with no batch dependency and no cluster state to maintain across TTL expiry. It removes the embedding pipeline and keeps an unvalidated model out of the scoring path. The implementation reduces to Redis-native commands, which removes the Python dependency and holds the service to single-language Java (ADR-01). ADR-11 covers the atomic Lua script that runs the multi-radius check.

The method forgoes two capabilities. It produces no explicit cluster identifiers or groupings. Offline connected-component analysis over flagged neighbourhoods recovers that view for investigation in Phase 2, where DBSCAN chaining is useful. The method also misses deliberate typosquatting that geocodes to distinct coordinates. That gap is accepted for the MVP and revisited if pattern analysis shows typosquatting to be material.
