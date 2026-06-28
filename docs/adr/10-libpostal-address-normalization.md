# ADR-10: Address Normalization via Libpostal

**Status:** Accepted

**Date:** June 2026

## Context

A single physical address can be typed many ways, but the geocoding cache needs each variant to resolve to one stable key.

## Decision

Run `pelias/libpostal-service` as a sidecar HTTP service. The canonical form is built by parsing the address into `{label, value}` components, sorting them by label, joining the pairs, and hashing the result with a server-side pepper into the cache key. The endpoint, join separator, and label-prefix role are in `geo-scoring/design.md` Step 1.

## Reasoning

Two simpler sources of normalisation were rejected first. The geocoder returns a normalised address, but only on a cache miss, so it cannot form the key that a cache hit must match before any geocoding call. Rules-based normalisation is brittle across European address formats and needs per-country maintenance. libpostal parses addresses locally with strong multilingual coverage and keeps the data off the network.

Sorting by label is the load-bearing invariant. libpostal does not guarantee a stable component order across versions or locales, but it does return the same set of label-value pairs. Sorting makes the canonical form deterministic, which is what allows the output to serve as a cache key.

Diacritics are not stripped before parsing. An earlier approach removed them, for example "ü" to "u". libpostal is trained on real addresses that contain diacritics, so stripping them degrades parse quality, and Nominatim handles diacritics downstream in any case. The step was unnecessary and potentially harmful.

The failure model is layered and fails open. `LibpostalClient` separates input rejection, a non-transient 4xx that returns an empty component list, from transient outage, a 5xx, transport error, 408, or 429 that raises `TransientGeocodingException`. `AddressNormalizationService` catches both at one fallback point. When libpostal is unreachable or returns nothing, the flattened raw string is used under a synthetic `address` label. That lowers the cache hit rate but does not block scoring, because the Nominatim free-form query can still resolve the address. The retry chain is reserved for Nominatim, the only path to coordinates, rather than for libpostal.

Running the parser out of process is deliberate. libpostal loads its language models into memory and holds a fixed multi-GB floor regardless of request rate. As a sidecar it keeps that cost off the geo-scoring JVM, which carries only a thin `RestClient`, so geo-scoring replicas stay light and scale on their own I/O profile.

## Consequences

Cache keys are deterministic and independent of input variation. Parsing is local with no external network call, which keeps the step GDPR-clean, and European coverage comes from libpostal's language models. The costs are a required runtime dependency, a lower cache hit rate during a libpostal outage, and a 30 to 60 second model-loading startup that readiness probes must allow for. The sidecar is a static singleton with a memory floor of roughly 2 to 3 GB that is independent of replica count. At 50K enrollments per day one replica suffices, and horizontal replication is a memory-cost decision deferred until `/parse` throughput rather than geo-scoring scaling demands it.
