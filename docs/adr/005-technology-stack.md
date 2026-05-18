# ADR-005: Technology Stack — Java, RabbitMQ, Lettuce, PostgreSQL, Spring MVC

**Status:** Accepted
**Date:** May 2026

## Context

Four stack choices needed: implementation language, message broker for event-driven
communication, Redis client for geo operations, and database for decision-engine
correlation state.

## Decision

### Language — Single-Language Java (no polyglot sidecar)

A polyglot architecture — Java for decision-engine, Python for ML/NLP/geospatial —
was considered to leverage Python's ecosystem strengths in scikit-learn (DBSCAN),
sentence-transformers (embeddings), and geospatial libraries. With DBSCAN and
vector embeddings both ruled out for density detection (ADR-001), no remaining
operation requires Python's ecosystem: geocoding is an HTTP call to Nominatim
(Java `RestClient`), Redis `GEOSEARCH` is supported natively by Lettuce, and
address normalisation is delegated to a libpostal HTTP sidecar (ADR-012). All
services are therefore implemented in Java — a single deployment artifact per
service, no inter-language communication overhead, no Python runtime dependency.
Revisit if ML-based scoring is added.

### Message Broker — RabbitMQ

Evaluated: SQS (simplest, fully managed), Kafka (highest throughput, partitioned log), RabbitMQ (mature routing, lightweight).

Kafka's partitioned log model and operational overhead (KRaft/Zookeeper) are not justified at ≤50,000 events/day. SQS doesn't run locally without LocalStack. RabbitMQ runs cleanly in Docker, has mature Spring Boot integration (Spring AMQP), and its topic exchange routing model is a natural fit for scatter-gather. Dead-letter queues, message TTL, durable queues, and header-based OpenTelemetry trace propagation are available out of the box.

### Redis Client — Lettuce

Lettuce is the default Redis client in Spring Boot (via Spring Data Redis). Supports non-blocking I/O, connection pooling, and all Redis commands including `GEOADD` and `GEOSEARCH`. Geo operations are executed via Lua scripts on `StringRedisTemplate` rather than through Spring Data's geo operations wrapper, to keep the density check and index write atomic (ADR-014). Jedis is simpler but uses blocking I/O. No capability gap with Lettuce.

### Correlation State — PostgreSQL

The scatter-gather correlation record (ADR-003) requires atomic check-and-update operations within a single transaction. PostgreSQL provides ACID transactions that make these guarantees trivial. Redis would require Lua scripting that grows complex as parallel checks increase. The correlation table is tiny (one row per in-flight request). RDS Free Tier provides a managed `db.t4g.micro` instance.

### Spring Programming Model — MVC (Servlet)

All modules — including the gateway — use Spring MVC (servlet stack). Spring WebFlux is explicitly excluded from the stack.

Spring Cloud Gateway has two implementations: the original reactive (WebFlux) implementation and a newer MVC-based implementation (`spring-cloud-starter-gateway-server-webmvc`). The MVC variant is used here for consistency. Introducing WebFlux into any module would bring a second programming model, second concurrency model (reactive streams vs. threads), and second testing model into a codebase where all other modules are MVC. At ≤50,000 events/day the throughput argument for reactive does not apply.

Virtual threads (ADR-008) provide non-blocking I/O behaviour on the MVC/servlet stack, removing the primary performance motivation for WebFlux.

## Consequences

**Gains:** Local development parity (all run in Docker Compose). Mature Spring Boot integration for all three. PostgreSQL ACID guarantees simplify correlation logic. RabbitMQ topic exchanges model scatter-gather naturally.

**Loses:** RabbitMQ requires self-managed infrastructure on AWS (Amazon MQ mitigates this). No native schema registry for event evolution (application-level concern).
