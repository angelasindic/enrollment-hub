# Account Service

Consumes `EnrollmentDecisionEvent`s from the `enrollment.decisions` topic exchange (routing key
`enrollment.decision.completed`), the decision-engine's outbound channel.

> **Stub.** The service records each decision once and logs its id, result and signal names. It
> provisions no account and persists nothing. It exists so the decision-engine's mandatory,
> confirmed publish has a real consumer end to end, and is the starting point for the account
> provisioning that sits outside the hub's scope (architecture.md §3).

## Message lifecycle

```
RabbitMQ                                  Account Service
enrollment.decisions  ──────────────────► EnrollmentDecisionListener
(enrollment.decision.completed)                   │
  └─► account-service.decisions.queue             ▼
                                          AccountProvisioningService  (dedup on decisionId → log)
```

## Channel ownership (ADR-13 §Channel Ownership)

The decision-engine owns the `enrollment.decisions` exchange. The account service owns everything on
the consuming side and declares it at startup: `account-service.decisions.queue`, its binding, and
the `account-service.decisions.dlx` / `account-service.decisions.queue.dlq` pair. It also declares
the exchange idempotently, so it can start before the decision-engine.

The decision-engine publishes with `mandatory=true`, so a decision is routable only once this
service has started against the broker at least once. Before that, every publish is returned
unroutable and the decision stays on the decision-engine's outbox (ADR-17). The queue is durable:
decisions published while the service is stopped wait on it.

## Delivery semantics

At-least-once + idempotent receiver. The same `decisionId` can arrive twice — a relay re-dispatch
after a lost publisher confirm, or a broker redelivery. `AccountProvisioningService` drops repeats
against a bounded in-memory set of recently seen ids (`account-service.dedup-capacity`, default
10000). The set does not survive a restart; a provisioning implementation records `decisionId`
under a unique constraint in the transaction that creates the account.

The listener retries 3× with exponential backoff; on exhaustion the message dead-letters to
`account-service.decisions.queue.dlq`. A message that cannot be deserialized is not retried and
dead-letters on the first throw.

The enrollment snapshot in the event carries personal data and is not logged.

## Run

Requires RabbitMQ (see root `docker-compose.yml`). Default port `8083`; actuator at
`/actuator/health` and `/actuator/prometheus`.
