# ADR-13: Decision Engine Messaging Architecture

**Status:** Accepted

**Date:** June 2026

## Context
The decision engine must evaluate enrollment requests against an open-ended, evolving set of signals (such as fraud detection, risk scoring, or routing-specific checks). The architecture must be extensible to integrate new signal types without breaking or reworking existing components.

Furthermore, enrollment validation is end-to-end asynchronous by design, dependent on heterogeneous internal and external services with variable latency that can extend a single validation workflow to several seconds. The system must tolerate mid-flight process restarts and infrastructure outages without losing visibility into dispatched checks or compromising evaluation state integrity.

## Decision
The decision engine coordinates each enrollment as a scatter-gather lifecycle distributed across four distinct, decoupled message exchanges:

1. **Ingress Layer (Intake):** A point-to-point boundary that accepts inbound enrollment requests.
2. **Command Dispatch (Request):** Dispatches individual, isolated check requests out to dedicated workers.
3. **Signal Collection (Result):** Collects worker replies asynchronously against a durable correlation record.
4. **Egress Layer (Decisions):** Delivers the final computed verdict outbound to downstream services.

### Ingress Inversion & Consumer-Side State Machine
To shift the dual-write away from the synchronous ingress boundary, the entry point performs a single broker-durable write to the intake topic and returns 202 Accepted immediately. The actual dual-write (database checkpoint + downstream publish) is deferred to the intake consumer.
The consumer maintains an idempotency ledger with a PENDING → COMPLETED state machine. On first delivery, it inserts a PENDING record—guarded by a unique constraint—then performs an idempotent downstream publish, and finally transitions the record to COMPLETED before acknowledging the intake message. If the consumer crashes at any point before the final ack, the broker redelivers the message; the consumer reads the ledger state and either retries the publish (if PENDING) or acknowledges the duplicate (if COMPLETED). This ensures no downstream command is lost and no duplicate storm is created, provided the downstream publish is itself idempotent.

#### Alternatives at the entry point

The entry point faces a dual-write: a correlation record must be inserted into PostgreSQL and the check commands dispatched to RabbitMQ, and no transaction spans both. The direction of the gap determines the symptom. If the publish precedes the commit, the broker can deliver a command to a fast check service before the uncommitted `INSERT` is visible under read-committed isolation, and the result handler finds no correlation row and drops the result. If the publish follows the commit, a crash before the publish completes leaves an orphan record: acknowledged to the applicant, known to the database, never dispatched. Three shapes were evaluated.

| Shape | Verdict | Reason |
|---|---|---|
| Transactional outbox | Rejected at ingress | Correct, but adds an outbox table, a relay, and polling-latency monitoring for an intent the broker already holds durably |
| Post-commit synchronization hook | Rejected | Closes the publish-beats-commit race but leaves the orphan window open: the hook runs in process memory, so a crash between commit and hook loses the event with no broker redelivery |
| **Inverted write order (this ADR)** | **Accepted** | The REST endpoint publishes one durable intake message and does nothing else; a single consumer owns the insert and the dispatch, and broker redelivery covers every crash point |

#### Intake ledger mechanics

The intake listener runs the `PENDING → COMPLETED` state machine across separate transactions:

1. **Insert PENDING.** An inner `@Transactional` service commits the correlation record via `INSERT ... ON CONFLICT (enrollment_id) DO NOTHING` with `intake_status = PENDING` before control returns to the listener. The primary key makes the insert idempotent: a redelivery, or a concurrent insert under horizontal scaling, writes no second row.
2. **Dispatch.** One check command per applicable signal, each blocking on the broker publisher confirm (`waitForConfirmsOrDie`), so "the publish succeeded" means the broker has confirmed it.
3. **Mark COMPLETED.** Committed before the intake message is acknowledged.

Acknowledgment is `AUTO`, so the intake message is acked only after the listener returns. A publish *exception* is retried in-process and, if it never succeeds, dead-lettered (`RejectAndDontRequeueRecoverer`). A *crash* acks nothing, so the broker requeues the unacked message and redelivers it. A record already `COMPLETED` means the previous delivery dispatched and crashed before its acknowledgment, and the duplicate is acked with no re-dispatch. A record still `PENDING` means the previous delivery failed before completing, and the listener re-dispatches and completes.

This gives the same end-to-end durability as a transactional outbox without the extra table and relay. The intake queue is the durable record of intent, an unacknowledged message is the only cursor of work in flight, and `intake_status` is the completion marker. Because the correlation insert commits before any dispatch, no consumer on the internal bus can observe a check command for a correlation record that has not committed. The narrow window between a successful dispatch and the `COMPLETED` commit can produce a duplicate dispatch, which is harmless because every internal consumer is idempotent; the trade-off is a small volume of duplicate internal events in exchange for no outbox table and no relay at the current volume envelope.

The ledger does not replace the timeout policy (ADR-15); the two address different failure modes. The ledger closes the case where a trigger command is never published, relying on broker redelivery to recover a crash between the correlation commit and the dispatch. The timeout sweep closes the case where a check service goes silent after a correctly published trigger: Geo-Scoring downtime, a Redis partition, geocoding exhaustion. The single window where both could act is a crash between the correlation commit and the dispatch; intake-queue redelivery is the primary recovery, and the timeout sweep is the backstop if that redelivery is itself lost.

#### Ingress / egress asymmetry

The inversion is the right model at ingress specifically because the unit of intent, the inbound enrollment request, arrives as a message: the broker's at-least-once delivery already makes it durable, so a transactional outbox here would be redundant. The exit point is not symmetric. There the unit of intent, the decision, is born inside a database transaction with no upstream message to lean on, so durable emission requires the decision to be committed before it is published. That is why decision emission uses a persist-then-publish outbox on the correlation record (ADR-17) while ingress does not. The asymmetry is structural, intent-as-command at ingress versus intent-as-verdict at egress, not a matter of volume; an outbox at ingress remains available as a pure scaling option.

### Channel Ownership
Each party owns the channels it initiates. The decision engine explicitly owns the command request exchange and the queues feeding the workers. This command-driven approach ensures that the decision-engine retains authority over its routing topology and allows messages to safely buffer even if a check worker service is offline during a deployment.

### Delivery & Concurrency Guarantees
* **At-Least-Once Delivery:** Enforced across boundaries using publisher confirmations, requiring all participants to be idempotent.
* **Serialized State Transitions:** Concurrent updates to an active correlation record are serialized via pessimistic row locking.
* **Poller Throughput:** Scheduled batch routines optimizing system state transitions must utilize non-blocking "skip locked" semantics to maximize multi-worker throughput and prevent starvation.

## Consequences
### Gains
* **Single Source of Truth:** Route applicability configuration drives both the dispatch criteria and the completion predicate evaluation.
* **Least-Privilege Payloads:** Workers only receive data fields required for their specific evaluation.
* **Structural Isolation:** Ingress failures or worker restarts are isolated and absorbed by broker durability layers.

### Costs
* Increased message amplification (one distinct publish per applicable signal).
* Infrastructure overhead of maintaining dedicated dead-letter exchanges and queues per consumer boundary.