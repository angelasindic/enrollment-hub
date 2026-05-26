# Fraud-Detection Service

Consumes `fraud.check` command messages on the `enrollment.check.request` exchange, runs the fraud
evaluation, and emits a `FraudCheckResult` on `enrollment.check.result`. Applies to **both** payment
routes (CREDIT_CARD and INVOICE).

> **Stub.** The current implementation approves every enrollment unconditionally
> (`SignalOutcome.OK`). It exists to exercise the full request/reply wiring so a real fraud service —
> velocity checks, device fingerprinting, email-domain and IP-clustering analysis (architecture.md
> §10.1) — can replace `FraudDetectionService` with no decision-engine changes.

## Request lifecycle

```
RabbitMQ                         Fraud-Detection                         RabbitMQ
enrollment.check.request  ─────► FraudCheckRequestListener               enrollment.check.result
(fraud.check)                            │                               (fraud.check)
                                         ▼
                                  FraudDetectionService  (stub → OK)
                                         │
                                         ▼
                                  FraudCheckResultPublisher  ───────────►
```

## Channel ownership (ADR-003 §Channel ownership)

The decision-engine owns and declares `fraud.detection.requests.queue` (and its DLX/DLQ), so a
`fraud.check` command is routable before this worker is deployed. The worker attaches a
`@RabbitListener` to that queue by name and does **not** redeclare it. It declares only the result
exchange it publishes to (idempotently; the decision-engine also declares it).

## Delivery semantics

At-least-once + idempotent receiver (architecture.md §8.7). The listener retries 3× with exponential
backoff; on exhaustion the message dead-letters to `fraud.detection.requests.queue.dlq`. Publishes use
publisher confirms + mandatory routing, surfacing nacks/returns as exceptions
(`fraud_detection_publish_failures_total`).

## Run

Requires RabbitMQ (see root `docker-compose.yml`). Default port `8082`; actuator at
`/actuator/health` and `/actuator/prometheus`.
