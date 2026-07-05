# Runbook — Dead-Letter Queue Inspection and Replay

**Trigger:** the `DlqNonEmpty` alert (`monitoring/prometheus/rules/dlq-alerts.yml`) — one or
more messages have sat in a dead-letter queue for over five minutes. The `queue` label on the
alert identifies which DLQ.

A message reaches a DLQ in exactly two ways (ADR-13 §Delivery & Concurrency Guarantees):
the listener retry budget was exhausted (3 attempts, exponential backoff), or the failure was
classified non-retryable and routed on the first throw — currently only
`UnknownCorrelationException`, a signal result whose `enrollmentId` has no correlation row.
Nothing is discarded automatically; the DLQ is the containment point, and this runbook is the
manual half of that contract.

## DLQs in scope

| DLQ | Live queue it protects | Typical cause |
|---|---|---|
| `enrollment.intake.queue.dlq` | `enrollment.intake.queue` | malformed intake payload; DB down beyond retry budget |
| `geo.scoring.requests.queue.dlq` | `geo.scoring.requests.queue` | geo-scoring worker failure on a specific request |
| `fraud.detection.requests.queue.dlq` | `fraud.detection.requests.queue` | fraud worker failure on a specific request |
| `decision-engine.geo-score.results.queue.dlq` | `decision-engine.geo-score.results.queue` | unknown `enrollmentId` (fast-DLQ) or repeated handler failure |
| `decision-engine.fraud-check.results.queue.dlq` | `decision-engine.fraud-check.results.queue` | unknown `enrollmentId` (fast-DLQ) or repeated handler failure |

## Procedure

**1. Inspect.** Open the RabbitMQ management UI (local: `http://localhost:15672`,
`guest`/`guest`) → Queues → the DLQ from the alert → *Get messages* (requeue = true, so
inspection does not consume). Read the payload and headers; `x-death` carries the original
queue, the routing key, and the rejection count. Correlate with service logs via the
`traceparent` header / `enrollmentId` in the payload.

**2. Classify.**

- **Transient cause has passed** (dependency outage, DB restart): the message is replayable.
- **Poison pill** (malformed payload, unknown `enrollmentId`, contract mismatch): replaying
  will dead-letter it again. Fix the producer or accept the loss; keep the payload for the
  post-incident record before discarding.

**3. Replay** — republish to the message's **original exchange and routing key** (both in
`x-death`), not to the DLQ's exchange. Two options:

- Management UI: *Move messages* on the DLQ page, destination = the live queue. Requires the
  shovel plugins (`rabbitmq-plugins enable rabbitmq_shovel rabbitmq_shovel_management` — one
  time per broker).
- Manual: *Get messages* (requeue = false) to take the payload, then *Publish message* on the
  original exchange with the original routing key.

Replay is safe to repeat: every consumer is an idempotent receiver (intake dedups on
`enrollmentId` via the ledger; result handlers discard already-settled signals — ADR-13,
ADR-16), so a double replay converges to one effect.

**4. Discard** (poison pills only): *Purge* on the DLQ page, after step 2's record is saved.

**5. Verify.** DLQ depth back to zero (`rabbitmq_dlq_depth` in Prometheus, or the queue page);
the alert resolves after the next evaluation. For replayed intake/check messages, confirm the
affected enrollment reached a decision: the correlation row has `decision_result` set and,
after the sweep's next tick at the latest, `dispatched_at`.

## What this runbook does not cover

Undispatched **decisions** never reach a DLQ — a decided-but-unpublished enrollment sits on
the correlation row in the outbox state and is re-published automatically by the sweep
(ADR-17). That condition has its own alert (`StuckDecisionOutbox`) and needs broker/log
triage, not message replay.
