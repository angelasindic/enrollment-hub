# Local Observability Stack

Three-signal observability for the host-run services (see [architecture.md §8.3](../docs/architecture.md)):

- **Traces and logs** take the *push* path: each service exports OTLP over HTTP to the collector on `localhost:4318`, which forwards traces to Tempo and logs to Loki.
- **Metrics** take the *pull* path: Prometheus scrapes each service's `/actuator/prometheus` endpoint directly (config in [`../monitoring/prometheus/`](../monitoring/prometheus/)) and evaluates the alert rules there (`DlqNonEmpty`, `DecisionPublishFailures`, `StuckDecisionOutbox`).

Grafana is provisioned with all three datasources on startup — no manual configuration.

## Start / Stop

From the repository root:

```bash
# start (runs alongside the main infrastructure compose)
docker compose -f otel-local/docker-compose.yml up -d

# stop, keep stored traces and Grafana state
docker compose -f otel-local/docker-compose.yml down

# stop and wipe volumes (tempo-data, grafana-data) — fresh start
docker compose -f otel-local/docker-compose.yml down -v
```

The stack is optional: the services start and run without it (OTLP export failures are logged and dropped). Prometheus expects the services on the host — a target shows *down* until the corresponding service is running.

## URLs

| URL | Service | What you do there |
|---|---|---|
| <http://localhost:3000> | **Grafana** | The one UI you normally need. Anonymous access, Admin role, no login form. **Explore → Tempo** to search traces, **Explore → Loki** to query logs, **Explore → Prometheus** for ad-hoc metric queries. |
| <http://localhost:9090> | **Prometheus** | Scrape status under *Status → Targets* (one job per service: gateway :8079, authorization-server :9000, decision-engine :8080, geo-scoring :8081, fraud-detection :8082); alert rules and their state under *Alerts*. |
| <http://localhost:3200> | Tempo | HTTP API only — no UI. Queried through Grafana. |
| <http://localhost:3100> | Loki | HTTP API only — no UI. Queried through Grafana. |
| `localhost:4318` / `localhost:4317` | OTel Collector | OTLP ingest (HTTP / gRPC) — the endpoint the services push to; nothing to open in a browser. |

Port `55679` is mapped for the collector's zpages debug UI, but the `zpages` extension is not enabled in `otel-collector-config.yaml`; add an `extensions:` block if you need it.

## Seeing a trace end-to-end

1. Start the main infrastructure (`docker compose up -d` in the repo root), this stack, and the services.
2. Drive an enrollment through the system — [docs/e2e-manual-test.md](../docs/e2e-manual-test.md) has the full flow.
3. In Grafana → **Explore** → **Tempo**: search by service name (e.g. `decision-engine`). The enrollment trace spans gateway → decision-engine → RabbitMQ → geo-scoring/fraud-detection.
4. In Grafana → **Explore** → **Loki**: `{service_name="decision-engine"}`. Log lines carry `trace_id` in their structured metadata and `enrollmentId` from MDC; expanding a line shows a **View trace** button that opens the trace in Tempo (log→trace linking provisioned via `derivedFields` in `grafana-datasources.yaml`). If the button is missing on an existing `grafana-data` volume, reset with `down -v` — provisioning is read at startup.

## Troubleshooting

- **Duplicate datasources in Grafana** — a pre-provisioning `grafana-data` volume with manually-added datasources conflicts with the provisioned ones. Delete the manual entries or reset with `down -v`.
- **Prometheus targets down on Linux** — targets use `host.docker.internal`; the compose file maps it via `extra_hosts: host-gateway`, so this works on both Docker Desktop and native Linux. If a target is still down, the service itself isn't running or its port differs from `monitoring/prometheus/prometheus.yml`.
- **Collector receives but Grafana shows nothing** — the collector's `debug` exporter prints everything it receives: `docker compose -f otel-local/docker-compose.yml logs otel-collector` shows whether spans/logs arrive at all, which separates an export problem (service side) from a storage problem (Tempo/Loki side).
