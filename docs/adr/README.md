# Architecture Decision Records

One file per decision, numbered in the order the decisions were taken. Each records the context, the options considered, the trade-offs, and the triggers that would reopen it. The architecture document (`docs/architecture.md`) states each decision and cites the ADR by number; the ADR carries the argument.

| ADR | Title | Decision | Status |
|---|---|---|---|
| [ADR-01](01-technology-stack.md) | Technology Stack | Java only; RabbitMQ over SQS and Kafka; PostgreSQL; Spring MVC | Accepted |
| [ADR-02](02-account-service-boundary.md) | Account Service Boundary | The Account Service is the system of record and out of scope | Accepted |
| [ADR-03](03-security-architecture.md) | Security Architecture | The gateway authenticates; services validate the JWT and authorize | Accepted |
| [ADR-04](04-virtual-threads.md) | Virtual Threads over Reactive Streams | Virtual threads with Spring MVC; no WebFlux | Accepted |
| [ADR-05](05-authorization-server-persistence.md) | Authorization Server Persistence | JDBC-backed authorization state and user store | Accepted |
| [ADR-06](06-event-contract-shared-library.md) | Event Contract Ownership | One producer-owned module; a mismatch fails at compile time | Accepted |
| [ADR-07](07-separate-scoring-microservice.md) | Separate Scoring Microservice | Geo-Scoring is its own service behind asynchronous events | Accepted |
| [ADR-08](08-fixed-radius-not-dbscan.md) | Density Detection Algorithm | Fixed-radius `GEOSEARCH` counts; DBSCAN rejected | Accepted |
| [ADR-09](09-pluggable-geocoding-provider.md) | Geocoding Provider | Self-hosted Nominatim; the provider interface is a test seam | Accepted |
| [ADR-10](10-libpostal-address-normalization.md) | Address Normalization via Libpostal | A libpostal sidecar builds the canonical cache key | Accepted |
| [ADR-11](11-atomic-geo-density-lua-script.md) | Atomic Redis Geo-Index | One Lua script for the check and the write; per-member TTL | Accepted |
| [ADR-12](12-ttl-as-architectural-asset.md) | 48-Hour TTL as Architectural Asset | The TTL is the detection window, not only a storage limit | Accepted |
| [ADR-13](13-decision-engine-messaging-architecture.md) | Decision Engine Messaging Architecture | Four exchanges; intake inversion with a `PENDING → COMPLETED` ledger | Accepted |
| [ADR-14](14-signal-classification-model.md) | Signal Classification Model | Aggregate by classification; a scoring signal cannot reject | Accepted |
| [ADR-15](15-fail-open-timeout-policy.md) | Timeout Policy | DB-polled timeouts; fail-open or fail-closed by classification | Accepted |
| [ADR-16](16-concurrent-scatter-gather-completion.md) | Concurrent Scatter-Gather Completion | Row lock per enrollment; ack after commit; idempotency guard | Accepted |
| [ADR-17](17-decision-emission-outbox.md) | Decision Emission | Compute once, commit, then publish; the row is the outbox | Accepted |
| [ADR-18](18-prerequisite-payment-check-token.md) | Prerequisite Payment-Check Token | Server-to-server issuance; gateway custody; conditional validation | Accepted |
| [ADR-19](19-eidas-connector-jwt.md) | eIDAS Integration | Connector-issued JWT as a prerequisite gate; not built | Accepted |
| [ADR-20](20-correlation-row-pii-retention.md) | Correlation-Row PII Retention | Erase the payload at dispatch; keep the stripped row | Accepted |
