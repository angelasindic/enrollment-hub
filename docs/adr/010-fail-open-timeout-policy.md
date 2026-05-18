# ADR-010: Signal-Type-Specific Timeout Policy with DB Polling

**Status:** Accepted
**Date:** May 2026

## Context

The scatter-gather pipeline (ADR-003) dispatches parallel signals to independent services.
Each signal has a finite deadline. When a signal does not return a result within that
deadline, the decision-engine must decide:

1. **How to detect** that the deadline has passed.
2. **What decision to produce** when one or more signals have timed out.

Two timeout detection mechanisms were evaluated. Two business policies were evaluated for
the outcome.

### Timeout detection options evaluated

**Option A — DB polling with row-level locking.**
A `@Scheduled` job queries the correlation table for rows with `PENDING` signals whose
deadline has elapsed. For each candidate row, the job acquires a `SELECT FOR UPDATE` row
lock, re-checks the status under the lock, and transitions `PENDING → FAILED` only if
still `PENDING`. The result handler for each signal acquires the same lock before
writing, making the two operations mutually exclusive: a late-arriving result cannot race
the timeout transition.

> **Not yet implemented.** `EnrollmentRepository.findPendingTimeouts()` exists but no
> `@Scheduled` poller calls it yet.

**Option B — Broker-native TTL with dead-letter routing.**
Each `EnrollmentAccepted` message is published to a per-request "wait queue" with a
message TTL equal to the signal deadline. On expiry, RabbitMQ dead-letters the message to
a `timeout.processor` queue that the decision-engine consumes to trigger the timeout
transition.

### Business policy options evaluated

The two routes involve signals with fundamentally different roles in the decision engine,
which makes a single uniform policy incorrect.

**Fail-open (timeout → APPROVED):**
A timed-out signal is treated as a missing signal, not a negative signal. The enrollment
proceeds to approval. Customer friction is minimised at the cost of accepting accounts
that a completed signal might have flagged. Appropriate for signals whose absence does not
imply a negative outcome.

**Fail-closed (timeout → REJECTED):**
A timed-out signal is treated as an inability to verify. The enrollment is rejected until
it can be resubmitted with a functioning pipeline. Fraud risk is minimized at the cost of
rejecting legitimate accounts during infrastructure degradation. Appropriate for
`REQUIRED` signals where the result is the prerequisite for any approval.

## Decision

**Timeout detection:** Option A — DB polling with `SELECT FOR UPDATE` row-level locking.

**Business policy:** Signal-classification-specific — the policy is determined by the
signal's `GateClassification` (ADR-018), not applied uniformly:

| Signal                    | Classification                      | Timeout policy | Outcome              |
|---------------------------|-------------------------------------|----------------|----------------------|
| Geo-scoring               | `SCORING_SIGNAL` (CREDIT_CARD route) | Fail-open      | `APPROVED`           |
| Internal Fraud Detection  | `BEST_EFFORT` (both routes)         | Fail-open      | `APPROVED`           |
| Credit Card prerequisite  | Outside classification model        | Fail-closed    | `403` — no pipeline  |
| eIDAS prerequisite        | Outside classification model        | Fail-closed    | `403` — no pipeline  |

Note on prerequisite gates: neither the credit card nor the eIDAS check participates in the
scatter-gather pipeline. Both are synchronous JWT validations at the decision-engine entry
point (ADR-007, ADR-011). The timeout scenario for both is the JWKS endpoint being unreachable
at signature verification time. No correlation record is created on failure — the request
is rejected before the pipeline starts. The fail-closed policy is enforced at the HTTP
response layer, not via the correlation record.

**Rationale for the distinction.** Scoring signals and best-effort checks are probabilistic
inputs to an aggregated decision — their absence does not imply fraud, and blocking
legitimate accounts during a transient service outage is disproportionate. Prerequisite
gates (credit card, eIDAS) are legal and compliance requirements that must be satisfied
before any pipeline starts: their absence or unverifiability is not a missing signal — it
is an unresolved precondition. Approving fail-open on a prerequisite gate would bypass the
requirement the gate exists to enforce.

**Solves:** Race condition between the timeout poller and a late-arriving signal result.
Legitimate accounts are not blocked by transient outages in scoring services. Hard-gate
compliance requirements are preserved even under eIDAS provider degradation.

**Doesn't solve:** Fraud that completes before the timeout window (the signal ran and
returned clean; the account may still be fraudulent). Late-arriving scores after a
decision has been emitted — compensating logic for post-decision reversals is out of
scope.

**Trade-off:** Fail-open on scoring signals accepts a bounded fraud exposure window
during infrastructure degradation. At the current volume (≤50,000 accounts/day) with
independent failure isolation (QG-2, §1.5), sustained outages are expected to be
infrequent and short-lived. Fail-closed on the prerequisite gate accepts customer friction
during eIDAS provider degradation; INVOICE applicants must resubmit when the provider recovers.

**Simplicity gate:** Both detection mechanisms ultimately require the decision-engine to
process a timeout signal and write to the correlation record. DB polling keeps all
timeout logic in one place (the decision-engine's scheduler and the correlation table),
observable via a standard SQL query. Broker-native TTL distributes timeout logic across
the message broker topology and the decision-engine consumer, adding a second infrastructure
concern without eliminating the correlation record dependency.

**Reversibility:** Easy for detection mechanism — DB polling can be replaced with
broker-native TTL independently of the business policy. Moderate for business policy —
changing fail-open to fail-closed requires a classification update and a redeployment;
existing in-flight requests at the time of change will use the old policy.

**Trigger to reconsider Option B (broker-native TTL):** The polling job becomes a
measurable bottleneck — detectable when timeout transitions lag behind actual deadlines
under sustained high load. At ≤5 RPS peak this is not a concern; revisit at ≥50 RPS
sustained.

**Trigger to reconsider fail-closed on scoring signals:** Post-launch fraud pattern
analysis reveals that timed-out signals correlate with fraud outcomes at a statistically
significant rate, suggesting that signal-service degradation is being induced deliberately
(availability attack to suppress scoring).

**Trigger to reconsider fail-open on prerequisite gates:** Regulatory guidance changes
such that verification failure during provider outages must not result in any approval for
either route, requiring a queued-retry model rather than immediate rejection.

## Consequences

**Gains:** Timeout detection is fully observable — in-flight requests, timed-out signals,
and pending transitions are all queryable from the correlation table with no broker-side
inspection required. No additional RabbitMQ topology (wait queues, per-message TTL,
secondary DLX routing) required. Row-level locking eliminates the race between the poller
and late-arriving results without requiring idempotency logic at the application layer
for this specific race.

**Loses:** Fail-open creates a bounded fraud exposure during signal-service outages.
Documented and accepted at current volume. DB polling introduces a scheduler dependency
in the decision-engine; the polling interval is a tunable that affects how quickly timeouts
are detected. A polling interval that is too long causes `PENDING` rows to sit past their
deadline, delaying the final `EnrollmentDecisionEvent` emission.
