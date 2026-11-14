# ADR-09: Geocoding Provider — Nominatim (Self-Hosted)

**Status:** Accepted

**Date:** June 2026

## Context

The geocoding provider was an open choice between Google Maps and self-hosted Nominatim. Google Maps offers superior accuracy but introduces per-request cost, rate limits, and caching constraints in its terms of service. For a portfolio MVP those constraints are not justified.

The choice also decides who receives applicant addresses. A geocoding request carries the address in its query string, so whichever provider answers it sees every address the hub processes.

## Decision

Use self-hosted Nominatim (`mediagis/nominatim`) as the geocoding provider. No provider abstraction is built for runtime migration. The `GeocodingProvider` interface is kept only as a unit-test seam that Mockito stubs in `GeocodingServiceTest`, and it carries no selection logic. Local development runs the same `NominatimGeocodingProvider` as production against the Docker Compose instance.

## Reasoning

The deciding reason is data protection. A hosted geocoder would become a processor under Art. 28 GDPR for every enrollment address, which needs a data processing agreement, and a US provider adds a third-country transfer under Chapter V with its own safeguards. Self-hosting keeps the address inside the hub's own boundary. That is the same data minimisation the rest of the design enforces: address-free logs and a keyed-hash geocoding cache (architecture §8.2), the 48-hour geo-index TTL (ADR-12), and payload erasure at dispatch (ADR-20). It also means no critical function depends on a single external provider (architecture §8.5).

Nominatim also removes the commercial constraints at once. Self-hosting carries no API cost, quota, or key management. The cache TTL becomes a purely technical decision rather than a contractual one, because no terms of service govern how long a result may be retained. Google Maps Platform, for comparison, allows cached coordinates to be kept for at most 30 days, while the geocoding cache here defaults to 90 (architecture §8.2). The provider runs under Docker Compose alongside Redis, RabbitMQ, and libpostal, so the full stack starts with no external credentials. Accuracy is sufficient for the task. Density detection operates at radii of 100m and above, street-level resolution meets that, and libpostal normalisation reduces address variants before a request reaches the geocoder.

The normalisation step and the Redis geocoding cache are retained even though Nominatim is free. A cache hit returns in about 0.1ms against roughly 50ms to 200ms for a Nominatim lookup, so the cache still pays for itself on repeat addresses. Running the full normalise, cache-lookup, geocode, and cache-store pipeline at zero cost also validates it locally. Normalisation ensures that variants such as "123 Main St" and "123 main street" map to one cache key.

## Consequences

There are no third-party quota or terms-of-service concerns, no API key rotation, and no per-request cost accounting. Request-path latency is set by Nominatim at roughly 50ms to 200ms rather than by an external network, and the Redis cache absorbs repeat lookups at about 0.1ms.

The cost moves to two other places. The first start imports the configured PBF extract, which takes minutes to hours depending on region size. That is a deployment-time concern and does not affect the request path. The local footprint is also heavier than a SaaS provider, because Nominatim adds a Postgres-backed container with its own memory and disk profile. PBF region selection, import times, volume layout, and sizing guidance are in `geo-scoring/README.md`.

The same weight makes the system hard to show. Anyone who wants to see it running has to import an extract before the first address resolves, so in practice readers of the repository see the design but never the running system. A lighter demo path is recorded in the README under "What I'd Do Differently".
