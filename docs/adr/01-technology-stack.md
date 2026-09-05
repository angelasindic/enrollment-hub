# ADR-01: Technology Stack — Java, RabbitMQ, Lettuce, PostgreSQL, Spring MVC

**Status:** Accepted

**Date:** June 2026

## Context

Four stack choices: implementation language, message broker, Redis client for geo operations, and the database for decision-engine correlation state.

## Decision

**Language — single-language Java.** A polyglot split (Java + Python for ML/NLP/geospatial) was considered for Python's scikit-learn (DBSCAN), embeddings, and geospatial libraries. With DBSCAN and vector embeddings ruled out for density detection, nothing left needs Python: geocoding is an HTTP call to Nominatim, Redis `GEOSEARCH` is native in Lettuce, and address normalisation goes to a libpostal HTTP sidecar. All services are Java — one deployment artifact per service, no inter-language overhead, no Python runtime. Revisit if ML-based scoring is added.

**Message broker — RabbitMQ.** Evaluated against SQS (managed, but needs LocalStack to run locally) and Kafka (whose partitioned-log model and KRaft/Zookeeper overhead aren't justified at ≤50,000 events/day). RabbitMQ runs cleanly in Docker, has mature Spring AMQP integration, and its topic-exchange routing fits scatter-gather naturally — with dead-letter queues, message TTL, durable queues, and OpenTelemetry trace propagation available out of the box.

**Redis client — Lettuce.** The Spring Boot default (via Spring Data Redis), fully featured (`GEOADD`/`GEOSEARCH`), used in blocking mode under virtual threads. Geo operations run as Lua scripts to keep the density check and index write atomic. Jedis is simpler but blocking-I/O only; no capability gap with Lettuce.

**Correlation state — PostgreSQL.** The scatter-gather correlation record needs atomic check-and-update in a single transaction; Postgres ACID makes that trivial, where Redis would need Lua that grows complex as parallel checks increase. The table is tiny — one row per undecided enrollment.

The Spring web model (MVC servlet, not WebFlux) is decided in ADR-04, alongside the virtual-threads choice that motivates it; it is noted here only to complete the stack inventory.

## Consequences

**Gains:** Local development parity (everything in Docker Compose); mature Spring Boot integration for broker, Redis, and database; Postgres ACID simplifies correlation logic; RabbitMQ topic exchanges model scatter-gather naturally.

**Loses:** RabbitMQ forgoes Kafka's partitioned-log replay and high-throughput headroom — acceptable at ≤50,000 events/day, but a constraint to revisit if volume grows by an order of magnitude. Single-language Java forgoes direct use of Python's ML and geospatial ecosystem (scikit-learn, embeddings); if ML-based scoring is added later, a polyglot split or a Python sidecar comes back onto the table.
