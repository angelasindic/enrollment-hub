# Geo-Scoring Service

Subscribes to `enrollment.created.credit_card` events, geocodes the shipping address, performs multi-radius density checks against a Redis geo-index, and emits `GeoScoreResult`.

## Request Lifecycle

```
RabbitMQ                Geo-Scoring                                                    RabbitMQ
enrollment.events  ───► EnrollmentAcceptedListener ──► GeoScoringService               enrollment.events
(enrollment.created                                          │                         (geo.score.completed)
 .credit_card)                                               ▼
                                                       GeocodingService
                                                             │
                          Address → libpostal (normalise) → HMAC-SHA256 cache key
                                                             │
                                                       Redis cache lookup
                                                       ├─ hit  → cached coordinates
                                                       └─ miss → Nominatim → cache store
                                                             │
                                                             ▼
                                                       GeoIndexService.checkAndIndex
                                                       (atomic Lua: 3× GEOSEARCH + GEOADD + ZADD)
                                                             │
                                                             ▼
                                                       DensityResult → RiskLevel
                                                             │
                                                             ▼
                                                       GeoScoreResultPublisher  ─────► (geo.score.completed)
```

On geocoding failure the density check is skipped and a `GeoScoreResult` with `riskLevel=null` +
`noResultReason="geocoding_failed"` is emitted instead (decision engine treats this as fail-open per ADR-010).
The 48-hour per-member TTL on the geo-index is enforced out of band by `GeoIndexCleanupJob` (see design.md).

### Geocoding Provider Selection (ADR-013)

ADR-013 commits to Nominatim as *the* geocoding provider — no generic pluggable abstraction was built for future
swapping. A second implementation (`MockGeocodingProvider`) is retained only for offline development and CI where
Nominatim is unavailable. Both implement the `GeocodingProvider` interface and are selected via
`@ConditionalOnProperty` on `geocoding.provider`:

| Provider | Config value | When to use |
|---|---|---|
| `NominatimGeocodingProvider` | `nominatim` | Production and local dev with the full container stack |
| `MockGeocodingProvider` | `mock` (default when `geocoding.provider` is unset) | Offline dev or CI without infrastructure containers |

Set via environment variable: `GEOCODING_PROVIDER=nominatim` (or `mock`).

## Nominatim and PBF Extracts

Nominatim is a self-hosted geocoding engine that uses [OpenStreetMap](https://www.openstreetmap.org/) data. It runs its own PostgreSQL database — on first startup it downloads and imports a `.osm.pbf` file (PBF = Protocolbuf Binary Format), which contains all streets, buildings, and addresses for a geographic region.

[Geofabrik](https://download.geofabrik.de/) provides extracts at different granularities:

| Region | PBF size | Import time | Use case |
|---|---|---|---|
| Monaco | ~500 KB | ~30 seconds | CI / integration tests |
| Netherlands | ~1.2 GB | ~15-30 minutes | Development (matches sample addresses) |
| Germany | ~4 GB | ~1-2 hours | Broader European coverage |
| Europe | ~28 GB | ~12-24 hours | Full European production dataset |
| Planet | ~75 GB | ~2-4 days | Global coverage |

### Docker Compose (development)

The `docker-compose.yml` in the project root runs Nominatim with the **Netherlands** extract by default — this covers the sample addresses (e.g., Keizersgracht 1, Amsterdam). Data is persisted in a named volume (`nominatim-data`) so the import only runs once.

```bash
# Start with default Netherlands extract
docker compose up nominatim

# Or use a different region
NOMINATIM_PBF_URL=https://download.geofabrik.de/europe/germany-latest.osm.pbf docker compose up nominatim
```

First startup takes 15-30 minutes (Netherlands import). Subsequent startups are instant.

### Integration tests

Integration tests use a **Monaco** extract (~500 KB, ~30s import) via Testcontainers. Monaco is the smallest available country extract — just enough to verify the Nominatim API contract without waiting for a large import. Tests resolve real Monaco addresses (e.g., "Avenue de Monte-Carlo, Monaco").

## Running

### Prerequisites

```bash
docker compose up -d  # Redis, RabbitMQ, libpostal, Nominatim
```

### Tests

```bash
# Unit tests only (no containers needed)
./mvnw test -pl geo-scoring -Dtest='!*IT'

# Integration tests (requires Docker)
./mvnw test -pl geo-scoring

# Single test class
./mvnw test -pl geo-scoring -Dtest=NominatimGeocodingProviderIT
```

### Configuration

| Property | Env variable | Default | Description |
|---|---|---|---|
| `geocoding.provider` | `GEOCODING_PROVIDER` | `nominatim` | Active geocoding provider (`nominatim` or `mock`) |
| `nominatim.host` | `NOMINATIM_HOST` | `localhost` | Nominatim hostname |
| `nominatim.port` | `NOMINATIM_PORT` | `8088` | Nominatim port |
| `geocoding.cache.hmac-secret` | `GEOCODING_CACHE_HMAC_SECRET` | *(required)* | HMAC-SHA256 pepper for cache keys |
| `geocoding.cache.ttl` | `GEOCODING_CACHE_TTL` | `P90D` | TTL applied to cached coordinates on store (ISO-8601 duration) |
| `geo-index.key-prefix` | `GEO_INDEX_KEY_PREFIX` | `geo` | Prefix for geo-index Redis keys (`{prefix}:{countryCode}`) |
| `geo-index.search-limit` | `GEO_INDEX_SEARCH_LIMIT` | `200` | COUNT cap for GEOSEARCH — truncation at this limit raises `riskLevel` to `EXTREME` |
| `geo-index.ttl` | `GEO_INDEX_TTL` | `PT48H` | Per-member TTL window enforced by the cleanup job (ADR-004) |
| `geo-index.cleanup-interval` | `GEO_INDEX_CLEANUP_INTERVAL` | `PT1H` | Cadence of the scheduled cleanup job |
| `geo-index.cleanup-batch-size` | `GEO_INDEX_CLEANUP_BATCH_SIZE` | `1000` | Max members removed per Lua cleanup invocation |
| `geo-index.radii[].radius` | — | `100,250,500` | Search radii in metres |
| `geo-index.radii[].threshold` | — | `5,8,12` | Neighbor count threshold per radius |
| `geo-index.radii[].risk-level` | — | `HIGH,MEDIUM,LOW` | Risk level emitted when the corresponding radius threshold is met |
