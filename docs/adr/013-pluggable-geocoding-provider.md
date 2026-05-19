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

- **No third-party ToS or quota concerns** — cache TTL is a purely technical decision, and no API key rotation
  or per-request cost accounting is required.
- **Geocoding latency dominated by Nominatim, not network** — self-hosted Nominatim responds in ~50–200 ms;
  the Redis cache (≈0.1 ms) is what absorbs repeat lookups.
- **First-run import cost** — Nominatim's first start imports the configured PBF extract (~minutes to hours
  depending on region size). This is a deployment-time concern, not a request-path concern. Setup specifics —
  PBF region selection, import times, Docker volume layout, configuration knobs — are documented in
  `geo-scoring/README.md`.
- **Heavier local footprint than a SaaS provider** — running Nominatim adds a Postgres-backed container with
  its own memory and disk profile (see README for sizing guidance).
