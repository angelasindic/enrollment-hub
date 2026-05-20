### ADR-008: Virtual Threads over Reactive Streams

**Status:** Accepted  
**Date:** May 2026

**Decision:** Use Java 25 virtual threads for all services. Do not use Spring WebFlux. Do not use structured concurrency (`StructuredTaskScope`).

> **Implementation.** `spring.threads.virtual.enabled=true` is set, so Tomcat workers, `@Scheduled` tasks, `@Async`, and AMQP listeners all run on virtual threads. Each service still wires an explicit `VirtualThreadTaskExecutor` on its listener container factory to give consumer threads a service-scoped name prefix (e.g. `amqp-geo-`) for log and trace diagnostics; the executor is otherwise equivalent to the auto-enabled one. Per-service consumer sizing lives in each service's `design.md`; the sizing principle is in ADR-003 §Consumer concurrency.

**Context:** The system is I/O-bound — the geo-scoring service blocks on Nominatim geocoding calls and Redis `GEOSEARCH` commands, the decision-engine blocks on PostgreSQL transactions and RabbitMQ message handling. Concurrency model choice affects readability, debugging, and operational complexity.

Three approaches were evaluated:

1. **Spring WebFlux (Project Reactor).** Reactive, non-blocking I/O using `Mono`/`Flux` composition. Proven at scale; prior professional experience exists with this stack.
2. **Virtual threads only.** Finalized in Java 21 (JEP 444), supported in Spring Boot 3.2+. Blocking code that scales like reactive code, with no API changes required for existing Spring MVC, Spring AMQP, Spring Data JDBC, and Spring Data Redis usage.
3. **Virtual threads + structured concurrency.** Adds `StructuredTaskScope` (JEP 505, fifth preview in Java 25) for coordinating parallel subtasks with automatic cancellation and timeout handling.

**Reasoning:**

**Why not WebFlux.** The system's workload is I/O-bound at modest volumes (≤50,000 events/day at peak). WebFlux's primary advantage — non-blocking I/O without thread-per-request exhaustion — is solved equally well by virtual threads, which allow blocking calls without consuming platform threads. WebFlux's differentiating strength is backpressure propagation for streaming workloads where producers can outpace consumers. This system has no streaming data flow — the decision-engine processes discrete account creation requests, and the event-driven architecture (RabbitMQ with bounded queues and consumer prefetch) provides natural flow control without reactive backpressure. The reactive programming model also imposes costs that are not offset here: `Mono.zip()` chains are harder to read than sequential blocking code, R2DBC is less mature than JDBC for the transactional guarantees the correlation record requires (ADR-003), and reactive stack traces complicate debugging.

**Why virtual threads.** All blocking operations in both services — JDBC transactions, Redis commands via Lettuce's synchronous API, Nominatim HTTP calls, RabbitMQ message consumption — run on virtual threads without code changes. Spring Boot 4.x handles this transparently: Tomcat request processing, `@Async` methods, `@Scheduled` tasks, and Spring AMQP listeners all execute on virtual threads when enabled. The geo-scoring service's stateless, I/O-bound processing (geocode → GEOADD → GEOSEARCH → emit result) benefits directly — each RabbitMQ message is handled on its own virtual thread with no thread pool sizing concerns.

**Why not structured concurrency.** `StructuredTaskScope` solves the problem of forking parallel subtasks within a single execution context, joining them with a timeout, and handling partial results. This would be a natural fit if the decision-engine dispatched checks synchronously and waited for results. However, this system uses event-driven scatter-gather via RabbitMQ (ADR-002, ADR-003): the decision-engine publishes an event to a topic exchange and returns immediately. The fan-out to check services (geo-scoring, identity, future additions) is handled by RabbitMQ's exchange routing, not by parallel forks in the decision-engine. Results arrive asynchronously as separate RabbitMQ messages — potentially minutes apart and across process restarts. The join mechanism is the durable correlation record in PostgreSQL (ADR-003), not an in-memory scope. `StructuredTaskScope` cannot span asynchronous message deliveries or survive process restarts — it is the wrong abstraction for this coordination pattern. Adopting it would require restructuring the architecture around synchronous dispatch, losing the failure isolation and durability that the event-driven design provides.

**What we lose:** WebFlux's backpressure propagation (not needed — event-driven architecture with RabbitMQ provides flow control). The maturity and breadth of community resources around reactive Spring patterns (mitigated by the simplicity of the blocking model — there is less to debug).

**Alternative considered:** Using virtual threads with structured concurrency for the scatter phase, forking parallel publish calls within a `StructuredTaskScope`. Rejected because (a) the RabbitMQ topic exchange already handles fan-out from a single publish, (b) even with explicit per-service publishes, each publish completes in sub-milliseconds with no I/O worth parallelizing, and (c) structured concurrency is a preview feature in Java 25 (JEP 505) requiring `--enable-preview` flags — complexity not justified without a genuine coordination need.
