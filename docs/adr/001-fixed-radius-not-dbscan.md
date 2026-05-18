# ADR-001: Density Detection Algorithm — Fixed-Radius GEOSEARCH

**Status:** Accepted
**Date:** May 2026

## Context

The geo-scoring service must detect Sybil attacks by identifying accounts clustered
in a tight physical area (an apartment building, a short block of adjacent
addresses). Two classes of alternatives were seriously considered before the simpler
fixed-radius approach was chosen:

1. **Density-based clustering (DBSCAN).** A natural candidate for spatiotemporal
   clustering — discovers density-based groups without a predefined cluster count.
2. **Semantic address matching (vector embeddings).** Sentence-transformer embeddings
   (e.g. `all-MiniLM-L6-v2`) combined with Haversine distance in a hybrid formula,
   intended to catch deliberate typosquatting ("101 Main St" vs. "101 Mian St").

## Decision

Use Redis `GEOSEARCH` fixed-radius neighbor counting. Reject both DBSCAN and vector
embeddings.

### Why not DBSCAN

DBSCAN builds clusters through *density-reachable chains* — if point A is near core
point B, and B is near core point C, then A and C join the same cluster even if A
and C are far apart. Chaining is appropriate where connectivity matters (galaxy
shapes, river contours) but mismodels the fraud threat: a fraudster controls a tight
physical area, not a chain of short hops. The business question is "how many
accounts exist within a fixed radius of this address?", not "is this address
connected through a chain of short hops?"

DBSCAN is also a batch algorithm requiring visibility of all points in a region to
determine core/border/noise membership. This creates tension with real-time scoring,
complicates cluster maintenance as points expire under the 48h TTL (ADR-004), and
imposes a per-account re-run cost that fixed-radius counting avoids.

A fixed-radius count using `GEOSEARCH` directly answers the business question with
no chaining artifact, eliminates the batch-vs-streaming tension (each evaluation is
independent and O(log n) via Redis's geo index), and composes cleanly with the
multi-radius risk-level mapping documented in the geo-scoring design.

### Why not vector embeddings

`all-MiniLM-L6-v2` was trained on natural language sentences, not short structured
address strings — its effectiveness on address-format inputs is unvalidated. The
hybrid distance formula (`D = α · Haversine + (1 − α) · Cosine`) combines metrics
with incompatible units (meters vs. unitless [0,1]) and requires normalisation that
adds complexity without proven benefit.

The formatting-variation problem that motivates semantic matching ("123 Main St" vs.
"123 main street") is already addressed upstream: libpostal normalisation (ADR-012)
canonicalises component order, casing, and abbreviations, and Nominatim geocoding
(ADR-013) resolves remaining variants to the same coordinates. Two inputs that
describe the same physical address converge on the same `(lon, lat)` before
`GEOSEARCH` sees them, so semantic similarity adds no detection surface that is not
already covered.

## Consequences

**Gains:**
- Real-time per-account scoring with no batch dependency.
- No cluster maintenance as points expire under the 48h TTL.
- No embedding pipeline to maintain and no unvalidated ML model in the scoring path.
- Direct alignment between detection method and threat model.
- Implementation reduces to Redis-native commands (see ADR-014 for the atomic Lua
  script that executes the multi-radius check).

**Loses:**
- Explicit cluster IDs and groupings. Mitigated by running connected-component
  analysis on flagged neighbourhoods for investigative purposes (Phase 2 — Batch
  Analytics), where DBSCAN's chaining property is useful offline.
- Detection of deliberate address typosquatting that produces distinct geocoding
  results. Acceptable gap for the MVP — revisit if fraud pattern analysis reveals
  typosquatting as a significant attack vector.

## Related

- ADR-012: Libpostal address normalisation — upstream canonicalization that removes
  most formatting variance before geocoding.
- ADR-013: Pluggable geocoding provider — resolves remaining variants to identical
  coordinates.
- ADR-014: Atomic Redis geo-index with per-member TTL — implements the multi-radius
  fixed-radius check via a single atomic Lua script.
- ADR-005: Technology stack — language choice (single-language Java) is a direct
  corollary, since no Python ecosystem is required once DBSCAN and embeddings are
  ruled out.
