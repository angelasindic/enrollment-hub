# ADR-15: Signal-Type-Specific Timeout Policy with DB Polling

**Status:** Accepted

**Date:** June 2026

## Context

The decision engine's scatter-gather (ADR-13) dispatches parallel signals to independent services, each with a finite deadline. When a signal does not return within its deadline, the engine must decide two things: how to detect that the deadline has passed, and what decision to produce when one or more signals have timed out. Two detection mechanisms and two business policies were evaluated.

### Detection options

**Option A — DB polling with row-level locking.** A `@Scheduled` job claims correlation rows whose deadline has elapsed while a signal is still `PENDING`, takes a `SELECT FOR UPDATE` lock on each, transitions the still-pending signals to `NotExecuted` (or, for a `REQUIRED` signal, to an explicit failed verdict), and runs the same finalize step the result handler runs (ADR-16, ADR-17). An enrollment whose remaining signals all time out is therefore decided and dispatched on the ordinary path, so emission depends on no single producer. The lock makes the timeout transition and a late-arriving result mutually exclusive, so neither races the other.

**Option B — Broker-native TTL with dead-letter routing.** Each dispatched command is published to a per-request wait queue with a message TTL equal to the deadline. On expiry RabbitMQ dead-letters it to a `timeout.processor` queue that the engine consumes to trigger the transition.

> **Scope.** This ADR fixes the timeout *policy* (classification-specific fail-open/closed) and the
> *detection mechanism* (Option A). The claim query is `EnrollmentRepository.claimPendingTimeouts()`;
> the `@Scheduled` wiring and the lock idiom are in `decision-engine/design.md`.

### Business policy options

A single uniform policy is incorrect because the routes carry signals with different roles.

**Fail-open (timeout → APPROVED).** A timed-out signal is treated as missing, not negative, and the enrollment proceeds. Customer friction is minimised at the cost of accepting accounts a completed signal might have flagged. Appropriate where a signal's absence does not imply a negative outcome.

**Fail-closed (timeout → REJECTED).** A timed-out signal is treated as an inability to verify, and the enrollment is rejected until it can be resubmitted against a healthy pipeline. Fraud risk is minimised at the cost of rejecting legitimate accounts during degradation. Appropriate for`REQUIRED` signals whose result is a precondition for any approval.

## Decision

**Detection:** Option A, DB polling with `SELECT FOR UPDATE` row-level locking.

**Policy:** determined by the signal's classification (ADR-14), not applied uniformly:

| Signal                   | Classification                       | Timeout policy | Outcome             |
|--------------------------|--------------------------------------|----------------|---------------------|
| Geo-scoring              | `SCORING_SIGNAL` (CREDIT_CARD route) | Fail-open      | `APPROVED`          |
| Internal Fraud Detection | `BEST_EFFORT` (both routes)          | Fail-open      | `APPROVED`          |
| Credit-card prerequisite | Outside the classification model     | Fail-closed    | `403` — no pipeline |
| eIDAS prerequisite       | Outside the classification model     | Fail-closed    | `403` — no pipeline |

The prerequisite gates do not participate in the scatter-gather. Both are synchronous JWT validations at the entry point (ADR-03), so their timeout case is the JWKS endpoint being unreachable at verification time. No correlation record is created, and the rejection is enforced at the HTTP layer rather than through the timeout poller.

**Rationale.** Scoring and best-effort checks are probabilistic inputs whose absence does not imply fraud, so blocking legitimate accounts during a transient outage is disproportionate. Prerequisite gates are legal and compliance requirements that must hold before any pipeline starts, so their absence is an unresolved precondition rather than a missing signal. Approving fail-open on a prerequisite gate would bypass the requirement the gate exists to enforce.

**Doesn't solve.** Fraud that completes within the deadline and returns clean. Late results that arrive after the decision is emitted, where post-decision compensation is out of scope and the late result is discarded (ADR-16, ADR-17).

**Trade-off.** Fail-open on scoring signals accepts a bounded fraud-exposure window during degradation. At the current volume (≤50,000 accounts/day) with independent failure isolation, sustained outages are expected to be infrequent and short. Fail-closed on a prerequisite gate accepts customer friction during provider degradation: affected applicants resubmit on recovery.

**Simplicity gate.** Both mechanisms ultimately require the engine to process a timeout and write the correlation record. DB polling keeps all timeout logic in one place, observable with a standard SQL query. Broker-native TTL spreads it across broker topology and a consumer, adding a second infrastructure concern without removing the correlation-record dependency.

**Reversibility.** Detection is easy to swap: DB polling can be replaced with broker-native TTL independently of the policy. Policy is moderate: changing a signal from fail-open to fail-closed is a classification change plus a redeploy, and enrollments already accepted use the policy in force when they were created.

### Triggers to reconsider

- **Broker-native TTL** if the polling job becomes a measurable bottleneck, that is, timeout transitions lag the deadlines under sustained load. Not a concern at ≤5 RPS peak; revisit at ≥50 RPS sustained (ADR-13; architecture §1.4).
- **Fail-closed on scoring signals** if post-launch analysis shows timed-out signals correlate with fraud at a significant rate, suggesting availability attacks that suppress scoring.
- **Queued retry instead of immediate rejection on prerequisite gates** if regulation requires that verification failure during a provider outage never yields an approval.

## Lock variant

The poller claims expired rows with `SELECT FOR UPDATE SKIP LOCKED`. The WAIT-versus-SKIP-LOCKED rationale is the shared correlation-record locking model in ADR-13 §Delivery & Concurrency Guarantees, and the JPA idiom is in `decision-engine/design.md §Repository: two locking idioms`. Replacing `SKIP LOCKED` here with optimistic or CAS locking is a single-call-site change that does not affect the handler path.

## Consequences

**Gains.** Timeout state is fully observable: undecided enrollments, timed-out signals, and pending transitions are all queryable from the correlation table with no broker-side inspection, and no extra RabbitMQ topology is required. Aggregate fail-open rate is a metric rather than a query — `decisionengine_signal_settled_total`, tagged by signal and terminal state, is recorded at the finalize step both completion paths converge on, and the `SignalFailingOpen` rule alerts on a sustained share. Without it the policy would be unmonitored by construction: a fail-open decision is by design indistinguishable, from the outside, from one taken on complete evidence.

**Costs.** Fail-open creates a bounded fraud-exposure window during signal-service outages, documented and accepted at current volume. DB polling adds a scheduler dependency, and the polling interval is a tunable: too long and `PENDING` rows sit past their deadline, delaying when the decision is recorded and dispatched (ADR-17).

> **Implementation note.** The scheduler is shared: timeout detection is the first phase of `EnrollmentSweepJob`, whose second phase is the ADR-17 dispatch backstop. Both are periodic `SKIP LOCKED` batch drains with loose latency tolerance, so they run as one scheduled sweep on one cadence (`decision-engine.sweep.interval`); the phases keep separate per-batch transactions and are contained independently. Rationale in ADR-17 §Amendment.
