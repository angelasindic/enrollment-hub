# ADR-013: Geocoding Provider — Nominatim (Self-Hosted)

**Status:** Accepted
**Date:** May 2026

## Context

The geo-scoring service geocodes addresses to detect geographic clustering. The geocoding provider was an open
decision between Google Maps (~28K free requests/month) and Nominatim (free, self-hosted). Google Maps offers
superior accuracy but introduces per-request costs, rate limits, and caching ToS constraints. For a portfolio MVP,
these concerns are not justified.

## Decision

Use **Nominatim** (`mediagis/nominatim`, self-hosted) as the geocoding provider for the portfolio MVP. No
pluggable provider abstraction is built for future migration — Nominatim is the provider.

A `MockGeocodingProvider` is retained for offline development and CI where Nominatim is unavailable.

**Why Nominatim:**
- No API costs or rate limits — self-hosted, no per-request pricing or quota monitoring.
- No caching ToS restrictions — cache TTL is a purely technical decision.
- Docker Compose parity — runs alongside Redis, RabbitMQ, and libpostal with no external API keys required.
- Sufficient accuracy — density detection operates at 100m+ radii; street-level accuracy is sufficient, and
  libpostal normalisation upstream reduces address variants before geocoding.

**Why libpostal normalisation and the geocoding cache are retained:**
Even with Nominatim at zero cost, a Redis cache hit (~0.1ms) is faster than a Nominatim lookup (~50–200ms).
The full normalise → cache lookup → geocode → cache store pipeline is validated at zero cost locally, and
libpostal normalisation ensures "123 Main St" and "123 main street" resolve to the same cache key.

## Consequences

- `docker-compose.yml` includes a `nominatim` service with a configurable PBF extract URL.
- `application.yml` configures `nominatim.host` and `nominatim.port`.
- Nominatim startup imports the PBF extract on first run (minutes for a single country); a named Docker volume
  persists imported data across restarts.
- No Google Maps API key or ToS review required.
