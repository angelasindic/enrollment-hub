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