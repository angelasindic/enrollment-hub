# ADR-20: Correlation-Row PII Retention — Erase the Payload at Dispatch, Keep the Decision

**Status:** Accepted — implemented

**Date:** September 2026

## Context

The correlation row stored `original_request` — the full enrollment payload — and never removed it, while architecture.md §8.2 documented the opposite posture and §10.1 carried retention as an open decision. The delete rule was already fixed in `decision-engine/design.md` §Retention ordering; nothing enforced it, and the blocking sub-decision — *what survives the payload* — was unanswered.

It blocks because the `decisionId → enrollmentId` linkage exists only on this row. ADR-17 withholds `enrollmentId` from the decision event, so deleting whole rows would destroy the bridge from a downstream decision back to this service along with the PII.

## Why the payload is on the row

The engine has no other source for it: the intake message is long acked and ADR-02 rules out asking the Account Service. The row holds it not for the decision, which never reads it, but for the outbound event that carries the enrollment downstream for account creation.

That is a consequence of the minimization already practised at the signal boundary — geo-scoring receives an address and no identity, so no worker can hand the whole payload back. Minimization in space is why the engine holds it at all; this ADR is minimization in time.

## Decision

**Erase `original_request` once the decision event carrying it has been confirmed by the broker. Retain the stripped row.**

The mechanism — the retention pass, its predicate, and how that predicate relates to the outbox claim — is in `decision-engine/design.md` §Retention ordering.

## The invariant

> **A correlation row holds the enrollment payload if and only if the decision it belongs to has not yet been delivered to the broker. The decision of record — ids, outcome, settled signals, timestamps — outlives the payload.**

After the stamp the payload has no reader: `claimUndispatched` selects `dispatched_at IS NULL` and `markDispatched` is guarded on it, so a stamped row is never re-claimed or re-published. The strip predicate is the exact complement of every state that still has a reader, which protects the timeout poller's rows for free — those have `decision_result IS NULL`, hence `dispatched_at IS NULL`, hence are unreachable at any age. `decisionengine.payload.oldest.age` is the reading that tests the invariant in production.

## Why there is no waiting period

An earlier draft kept a grace window, so the event could be rebuilt if the broker lost a message it had confirmed. That justification fails twice.

**No mechanism performs the recovery.** Nothing re-claims a stamped row, and no reconciliation against the consumer exists, so a lost decision is never detected and nothing acts on it.

**It is not this service's recovery to perform.** The message at issue is the `EnrollmentDecisionEvent` alone — the only thing the payload is read to build — and the Account Service owns the queue it lands on and that queue's dead-letter queue (ADR-13 §Channel Ownership). From the publisher confirm onward that message and its retention belong to them. (The engine's own intake and check-request queues are a different matter: it owns those and their DLQs, which is why their expiry is its problem.) A window here would hold personal data against a failure this service neither detects nor owns — the precise pattern this ADR exists to remove.

Queue ownership divides the engineering work, not the legal obligation, and the ADR should not be read as claiming otherwise. Under a single controller "their queue" is still that controller's storage; under two, a data-sharing or Art. 28 arrangement is required. Either way it is a pre-production task (§8.2).

## What survives, stated honestly

The stripped row keeps the ids, the payment type, the settled signal map, the outcome, and the timestamps. That is a **pseudonymous linkage and decision record, not an Art. 22 explainability record**, and the distinction is deliberate:

- It is still personal data. `enrollment_id` links to a person through the Account Service (Recital 26) — minimized, not anonymized.
- It does not explain a *scoring* decision. `GeoScoreResult` carries the neighbour counts and triggered radii behind a score; only the tier is persisted, on the row or on the event.
- **Retaining the payload would not have fixed that.** A geo score is a function of the address *and* the state of the geo-index at scoring time, and that index is 48-hour TTL by design (ADR-12). Keeping an address lets you re-geocode it; it does not let you re-derive the density that produced `HIGH`. The strip removes something that was never the evidence.

So the retained row is justified on what it demonstrably provides — the `decisionId → enrollmentId` bridge, and the §8.8 guard that a missing row turns benign late results into false inconsistencies — not on a traceability claim the schema does not support. Closing that gap means persisting the measurement at settle time (§10.1).

## Why the stripped row is not deleted

Deferred, not overlooked. The strip does essentially all of the compliance work. What a delete would remove is a UUID, a payment type, a signal map, an outcome, and timestamps: pseudonymous data with a live purpose. That is weak grounds for erasure and strong grounds for retention — it deletes the record wanted in a dispute after the sensitive data is already gone. The window stays open in §10.1.

## Options considered

| Option | Verdict | Reason |
|---|---|---|
| **Erase at dispatch; retain the stripped row** | **Accepted** | Removes the payload the moment it has no reader here; keeps the linkage and the late-arrival guard |
| Retain for a grace window after dispatch | Rejected | No mechanism performs the recovery it was meant to enable, and recovery of a delivered `EnrollmentDecisionEvent` belongs to the Account Service, which owns that queue — §Why there is no waiting period |
| Erase inside `markDispatched` | Rejected | Same invariant, but puts an erasure in the hot dispatch statement to save at most one retention pass |
| Erase, then delete the row after a window | Deferred | Most of the work, little of the win; destroys the `decisionId → enrollmentId` bridge; no defensible window yet |
| Time-partition and drop partitions | Deferred with the delete | A better implementation of a delete phase, not an alternative to the erase, which it cannot perform |
| Crypto-shredding — encrypt per enrollment, destroy the key at dispatch | Rejected | The one option that also neutralizes the payload in backups. Rejected as machinery — key store, rotation, and a failure mode where a lost key destroys an undispatched decision — bought to solve a backup-window problem a documented retention statement solves. Reconsider if backup retention grows long enough to matter alone |
| Move the data to the Account Service (§8.2 alternative) | **Target state, not buildable here** | If submission wrote there first and the engine worked on a reference plus per-signal slices, `original_request` would not exist. ADR-02 puts that service out of scope, so this ADR is the bounded interim, not the destination |
| Rely on the documented delete rule | Rejected | The rule already existed and enforced nothing |

## Residual exposure

Named rather than implied, because the ADR would otherwise overstate what it achieves: backups, WAL, and replicas until they age out of their own cycles; the superseded row version until its space is reclaimed; the three PII-bearing dead-letter queues, which have no expiry; the Account Service's own queue, outside this service; and the OTLP log pipeline, kept clean only by §8.2's rule that no address reaches a log, which no test pins.

## Consequences

**Doesn't solve:** the payload in backups and WAL until they age out; the DLQs; what the engine sends to the Account Service; the absence of persisted scoring evidence; erasure-request handling, which needed this survival decision but requires its own API and process.

**Trade-off:** erasure lags the stamp by up to one retention pass rather than being simultaneous with it, so the invariant reads "not dispatched, or dispatched within the last pass". Still a single assertable predicate, and the dispatch statement stays untouched.

**Reversibility:** one scheduled component, one statement, one nullable column. Moving to crypto-shredding, or to the Account Service holding the payload, replaces it behind the same invariant.

**Triggers to reconsider:** persisted scoring evidence, at which point the retained row becomes a genuine Art. 22 record and the disclaimer above can be withdrawn; outcome-scoped emission, which would stop `REJECTED` decisions carrying the payload downstream at all; backup retention long enough that crypto-shredding earns its machinery; row deletion once the DPIA sets a window; and the Account Service becoming real, which retires the reason for holding the payload here at all.

## Related ADRs

- **ADR-17 — Decision Emission Outbox.** Defines `dispatched_at` and the claim predicates this ADR's erasure is the complement of; the payload exists to serve that outbox.
- **ADR-02 — Account Service Boundary.** Why the engine holds enrollment data at all, and why moving that data there is the target state rather than this ADR.
- **ADR-13 — Messaging Architecture.** Channel ownership, which makes delivery-side retention and recovery the consumer's rather than this service's.
- **ADR-12 — TTL as Architectural Asset.** The same storage-limitation argument applied to the geo-index, and why a retained address could not reconstruct a geo score.
