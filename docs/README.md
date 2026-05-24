# Enrollment Hub

> A fraudster with twenty synthetic identities diversifies payment instruments, IP addresses, and device fingerprints.
> Standard fraud checks pass. The shared apartment address doesn't. The Enrollment Hub detects that cluster by scoring
> incoming enrollment addresses against a real-time geographic density index, with a type system that ensures a
> misconfigured threshold inflates the review queue but can never cause a wrongful rejection.

---

## What this is

The Enrollment Hub is an event-driven, asynchronous registration pipeline featuring geo-temporal fraud detection. It is
designed around the core requirements of an enterprise enrollment system: **data concurrency, automated GDPR compliance
built directly into the data lifecycle, and compile-time structural safety.**

Positioned between the registration frontend and downstream fulfillment services, the hub ingests enrollment requests,
fans out to parallel risk-evaluation services, and emits an asynchronous enrollment event. This decoupled
approach ensures applicant-facing latency remains entirely independent of downstream services with unpredictable or
unreliable execution times.

The system's important enhancement is a **Geo-Scoring module** designed to detect synthetic identity fraud. While advanced
fraud rings can randomize digital fingerprints and payment methods, they frequently rely on the same physical delivery
addresses. The Geo-Scoring module indexes and analyzes these addresses in real time to flag anomalous geographic
clustering.

> For a detailed look at the market validation, metrics, and rollout strategy for this module, 
> see the [Geo-Scoring Business Analysis](geo_scoring_business_analysis.md).

---

## What this demonstrates

### Asynchronous scatter-gather orchestration

Incoming HTTP requests are immediately offloaded to a durable intake queue, isolating the external API boundary from
background processing. The Decision Engine consumes from this intake queue and dispatches the requests across a RabbitMQ
topic exchange, fanning out to independent signal services based on payment type (credit-card / invoice). The engine
then correlates the incoming results and emits a terminal `EnrollmentDecisionEvent` once all applicable signals settle
or time out. This pattern handles concurrent result arrival, partial signal availability, and fail-open degradation
without requiring application-level coordination between services.

**Signal classification model (ADR-016).** Each signal declares a `GateClassification` that drives how the Decision
Engine treats its result and its absence:

| Classification   | Missing signal  | Authority over outcome                                   | Current assignment |
|------------------|-----------------|----------------------------------------------------------|--------------------|
| `REQUIRED`       | Blocks decision | Authoritative — any outcome                              | Reserved (future)  |
| `BEST_EFFORT`    | Fail-open       | Authoritative — any outcome                              | Fraud Detection    |
| `SCORING_SIGNAL` | Fail-open       | Advisory — `CONDITIONAL_APPROVED` only, never `REJECTED` | Geo-Scoring        |

The asymmetry is structurally enforced in the aggregation loop — the `SCORING_SIGNAL` branch can only set the
review flag; the rejection accumulator is physically unreachable from it. Misconfiguring a geo-density threshold
can inflate the analyst review queue but cannot cause wrongful rejection.

**Concurrent scatter-gather completion safety (ADR-015).** Result handlers acquire a pessimistic lock
(`SELECT FOR UPDATE`) on the correlation record before recording a signal result. This prevents lost-update races
when two results arrive simultaneously, without requiring distributed locks or application-level sequencing.

**JSONB-backed extensible signal registry.** The correlation record stores signal states as a
`Map<SignalConfig, SignalState>` in a single JSONB column. Adding a new signal requires only a new `SignalConfig`
enum value — no DDL migration, no entity field change. Routing and completion logic derive entirely from
`SignalConfig` metadata at runtime.

**Geo-spatial indexing on Redis (ADR-014).** The Geo-Scoring module geocodes enrollment addresses via self-hosted
Nominatim, normalizes them via libpostal, and indexes coordinates using an atomic Lua script (GEOSEARCH + GEOADD)
with a 48-hour per-member TTL. Enrollment density is computed across multiple configurable radii; saturating the
Redis `COUNT 200` result cap produces `RiskLevel.EXTREME` — a first-class risk level treated distinctly from `HIGH`.
A sorted-set companion index drives per-member eviction via a scheduled cleanup job.

**GDPR-motivated data architecture.** Prerequisite rejections never touch the database. Only requests that cross
into the pipeline create correlation records, satisfying GDPR data minimisation. The 48-hour TTL on the geo-index
serves the same principle for spatial data (ADR-004).

**Deliberate phased rollout design.** The Geo-Scoring module deploys under conservative thresholds from day one —
no shadow-mode infrastructure needed. The asymmetric aggregation rule ensures misconfiguration inflates the review
queue but cannot cause wrongful rejection. Phase 2 tightens thresholds based on observed density distributions.

**JDK 25 virtual threads (ADR-008).** Virtual threads are enabled on the AMQP listener container factory. The
global `spring.threads.virtual.enabled=true` is planned but not yet set.

---

## Implementation status

The core pipeline is implemented. Several items are planned but not yet built.

**Implemented:**
- RabbitMQ scatter-gather topology (`enrollment.events` topic exchange, geo-score result queue with DLQ)
- Durable intake queue (`enrollment.intake`, ADR-003 Layer 1) — `EnrollmentIntakeService` consumes and publishes `EnrollmentAccepted` post-commit
- Durable correlation record (PostgreSQL, JSONB signal map, `SELECT FOR UPDATE` concurrency guard)
- Decision Engine with ADR-016 signal classification model (BEST_EFFORT + SCORING_SIGNAL aggregation)
- Complete Geo-Scoring module (libpostal normalisation, Nominatim geocoding, atomic Redis Lua density check)
- Spring Cloud Gateway with Keycloak JWT validation (Layer 2 security)
- `EnrollmentDecisionEvent` published with `decisionId`, `originalRequest`, and settled signal results
- Dedicated outbound exchange (`enrollment.decisions`, ADR-003 Layer 3) — `EnrollmentDecisionPublisher` targets the dedicated exchange

**Not yet implemented:**
- Timeout poller (ADR-010) — `findPendingTimeouts()` query exists; `@Scheduled` job not wired
- Decision-engine as OAuth2 resource server + prerequisite token validation (ADR-007) — dependency commented out
- Fraud check result listener — `FRAUD_CHECK` signal exists in correlation record but no AMQP listener consumes `FraudCheckResult`
- Account Service consumer — `EnrollmentDecisionEvent` is published but no downstream consumer is implemented

---

## Tech stack

| Concern               | Technology                                                              |
|-----------------------|-------------------------------------------------------------------------|
| Runtime               | JDK 25, virtual threads                                                 |
| Framework             | Spring Boot 4.x, Spring AMQP                                            |
| Messaging             | RabbitMQ — topic exchange, scatter-gather topology                      |
| Persistence           | PostgreSQL — correlation record with JSONB signal map                   |
| Geo index             | Redis — atomic Lua GEOSEARCH + GEOADD, 48-hour per-member TTL          |
| Geocoding             | Nominatim (self-hosted)                                                 |
| Address normalisation | libpostal                                                               |
| ORM                   | Spring Data JPA, Hibernate                                              |
| Architecture diagrams | Structurizr DSL                                                         |
| Local infrastructure  | Docker Compose                                                          |
| Integration tests     | Testcontainers                                                          |

---

## Documents

| Document | What it covers |
|---|---|
| [Geo-Scoring Business Analysis](geo_scoring_business_analysis.md) | The synthetic identity fraud pattern, the gap in existing defences, the geo-temporal clustering rationale, and the phased rollout strategy |
| [Architecture Document](architecture.md) | System design, C4 component model, scatter-gather topology, ADR log, observability strategy, GDPR posture, and operational decisions |

---

## Running locally

Start infrastructure (RabbitMQ, PostgreSQL, Redis, Nominatim):

```bash
docker compose up -d
```

Run the hub:

```bash
./mvnw spring-boot:run
```

The integration test suite manages its own infrastructure via Testcontainers — no running Docker Compose instance is
needed:

```bash
./mvnw verify
```
