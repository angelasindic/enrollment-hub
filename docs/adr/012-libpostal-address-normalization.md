# ADR-012: Address Normalization via Libpostal

**Status:** Accepted  
**Date:** May 2026

## Context

The geocoding cache key is `HMAC-SHA256(normalised address, secret pepper) → lat/lon`. For the cache to be effective, the same physical address must always produce the same key regardless of how the user typed it. "123 Main St", "123 main street", and "123 Main Street" all refer to the same location, but produce different hashes from raw input.

Three options were considered:

| Option                                   | Trade-off                                                                                                                                                                                                  |
|------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Implicit geocoder normalization**      | The geocoding API (Nominatim) returns a normalized address in the response, but it is only available on a cache miss — a cache hit would bypass geocoding, so the key must be computed before the API call |
| **Regex / rules-based normalization**    | Fragile for European addresses, requires ongoing maintenance per country, and cannot handle abbreviation dictionaries                                                                                      |
| **libpostal (pelias/libpostal-service)** | Open-source NLP address parser with strong multilingual and European address support; exposes a local HTTP API — no data leaves the infrastructure                                                         |

## Decision

Use `pelias/libpostal-service` as a sidecar HTTP service for address normalization. The canonical form is built by
parsing the address into `{label, value}` components, sorting by `label`, and joining the pairs into a single
string that is then HMAC-SHA256 hashed (with a server-side pepper) into the geocoding cache key. The full
procedure — endpoint, join separator, and the role of the label prefix — is documented in
`geo-scoring/design.md` Step 1.

**Sort-by-label is the load-bearing invariant.** libpostal's response order is not guaranteed stable across
versions or locales, but the *set* of label-value pairs is. Sorting by label is what makes the canonical form
deterministic, and it is the reason this normalization can be relied on as the cache key.

**No pre-processing of diacritics:** an earlier approach stripped diacritics (e.g. "ü" → "u") before passing the address to libpostal. This was rejected because libpostal is trained on real-world addresses that include diacritics, and stripping them upfront can degrade parse quality. The output is ultimately passed to the Nominatim geocoding API, which also handles diacritics correctly. Pre-processing was therefore unnecessary and potentially harmful.

**Fail-open policy:** if libpostal is unreachable or returns no components, the flattened raw string is used as the cache key. This degrades cache hit rate but does not block geo-scoring.

## Consequences

**Gains:** Deterministic geocoding cache keys independent of input variation. GDPR-clean — address data is parsed locally with no external network call. Strong European address coverage via libpostal's language models.

**Loses:** Adds libpostal as a required runtime dependency. The fail-open policy means degraded cache efficiency under libpostal outages.

**Operational note:** `pelias/libpostal-service` has a 30–60 second startup time due to language model loading. Health checks and readiness probes must account for this.
