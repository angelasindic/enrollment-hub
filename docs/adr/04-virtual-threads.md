# ADR-04: Virtual Threads over Reactive Streams

**Status:** Accepted

**Date:** June 2026

## Decision

Use Java 25 virtual threads for all services. Use Spring MVC instead of Spring WebFlux. Do not use structured concurrency (`StructuredTaskScope`).

(`spring.threads.virtual.enabled=true` puts Tomcat workers, `@Scheduled`, `@Async`, and AMQP listeners on virtual threads. Each service still wires an explicit `VirtualThreadTaskExecutor` on its listener container — functionally equivalent to the auto-enabled one, but giving consumer threads a service-scoped name prefix (e.g. `amqp-geo-`) for log and trace diagnostics. Per-service consumer sizing lives in each service's `design.md` and ADR-13.)

## Context

The system is I/O-bound — geo-scoring blocks on Nominatim geocoding and Redis `GEOSEARCH`, the decision-engine on PostgreSQL transactions and RabbitMQ handling — at modest volumes (≤50,000 events/day at peak). Three approaches were evaluated: WebFlux (Project Reactor), virtual threads alone, and virtual threads plus structured concurrency.

## Reasoning

**Why not WebFlux.** Its primary advantage — non-blocking I/O without thread-per-request exhaustion — is matched by virtual threads, which allow blocking calls without consuming platform threads. Its differentiating strength, backpressure propagation for streaming workloads, isn't needed here: the system has no streaming flow, and RabbitMQ with bounded queues and consumer prefetch already provides flow control. The reactive costs aren't offset — `Mono.zip()` chains read worse than sequential blocking code, R2DBC is less mature than JDBC for the transactional guarantees the correlation record requires, and reactive stack traces complicate debugging. Introducing WebFlux anywhere would also impose a second programming, concurrency, and testing model on an otherwise-MVC codebase — including the gateway, which uses the MVC-based Spring Cloud Gateway variant (`spring-cloud-starter-gateway-server-webmvc`) rather than the reactive default, so the servlet model holds end to end.

**Why virtual threads.** Every blocking operation in both services — JDBC, Lettuce's synchronous Redis API, Nominatim HTTP, RabbitMQ consumption — runs on virtual threads with no code changes; Spring Boot handles Tomcat, `@Async`, `@Scheduled`, and AMQP listeners transparently. Geo-scoring's stateless I/O-bound path (geocode → GEOADD → GEOSEARCH → emit) gets one virtual thread per message with no pool-sizing concern.

**Why Java 25 specifically.**
JEP 491 removes virtual-thread pinning on synchronized monitors, which the blocking JDBC/Redis drivers rely on — without it, threads blocked inside a driver monitor could starve the carrier pool under load; HikariCP connection pools remains in the intentional back-pressure limit.

**Why not structured concurrency.**
Structured Concurrency fits synchronous in-process fan-out; our scatter-gather is async over RabbitMQ and the join is the durable correlation record, which a `StructuredTaskScope` can't span or survive — and it's still preview in 25

## Consequences

**Gains:** Every blocking call — JDBC, Lettuce's synchronous API, Nominatim HTTP, AMQP consumption — runs as written, with no reactive rewrite: sequential code that reads in execution order, ordinary stack traces, and the mature JDBC transactional path the correlation record needs. One programming, concurrency, and testing model across all services, the gateway included.

**Loses:** WebFlux's backpressure propagation.
