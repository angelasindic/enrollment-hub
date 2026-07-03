# ADR-17: Decision Emission — Compute-Once, Persist-Then-Publish (Transactional Outbox on the Correlation Record)

**Status:** Accepted — implemented; see §Amendment (2026-07-02)

**Date:** June 2026

## Context

When the applicable signals have settled, the decision-engine aggregates them into a `DecisionResult`(ADR-14) and must publish `EnrollmentDecisionEvent` to the Account Service (ADR-02). The decision is produced *inside* the database transaction that holds the `SELECT FOR UPDATE` lock on the correlation row (ADR-16, ADR-15). Emitting it to the broker therefore crosses PostgreSQL and RabbitMQ with no atomic commit spanning them: a publish issued inside `@Transactional` does not roll back with the transaction, and a committed transaction cannot un-send a publish. This is the dual-write problem on the exit side, the mirror of the entry-side inversion in ADR-13 §Ingress Inversion.

Two problems must be solved together. No atomic commit spans the database and the broker, so any ordering leaves a crash window. And the decision is produced on either of two paths — the result handler on the last arrival, or the timeout poller on the last deadline — so emission must follow whichever path produces it rather than being bound to one.

## Why the naive orderings fail

**Publish-then-commit** flushes to the broker before the commit. If the publish fails, the transaction rolls back cleanly and both systems stay consistent. The dangerous branch is a*successful* publish followed by a failed commit or a crash before COMMIT: the broker now holds an event the database never committed, a **phantom decision** that rollback cannot retract.

The phantom is worse than a duplicate, because recovery can produce a *different* decision. On the credit-card route with fraud failing, the handler computes `REJECTED`, publishes, then the commit fails and the fraud slot reverts to `PENDING`. If the timeout poller fires first on recovery, fraud is marked `FAILED`, `BEST_EFFORT` fails open, and it recomputes and publishes `APPROVED`. Downstream now holds two contradictory decisions.

Consumer idempotency does not cover this: it dedupes the same message arriving twice, not two different decisions. Keyed on `enrollmentId` it is worse — the consumer skips the divergent recompute as a duplicate and stays frozen on the first decision while the database commits the second, so the decision of record and the decision enforced diverge silently and permanently. No guard patches it, because the only durable "already published" marker would be the very commit that failed.

The root error is **recomputation**: recovery re-derives a decision that was already emitted, whether because a settled verdict was lost and defaulted or because a re-derived signal read different shared state (ADR-11). The decision must be persisted once and replayed, never recomputed.

## Decision

**Separate deciding from dispatching, and order them commit-then-publish.** The component that completes the row computes the decision and writes it to the correlation row under the lock, then commits — it does not publish. A separate `@Scheduled` relay claims decided-but-undispatched rows and publishes them out of band, stamping the row dispatched only after the publisher confirm returns. The correlation record is itself the transactional outbox; no separate outbox table is introduced.

The mechanism — the shared finalize step, the relay claim query, the row markers, the ordering rule, and the per-crash-point recovery — is in `decision-engine/design.md §Decision outbox and dispatch relay`.

## The invariant

> **A decision is computed exactly once and persisted before it is emitted; all delivery replays the
> persisted decision and never recomputes it. The decision of record (the correlation database) is
> therefore identical to the decision enforced downstream.**

In honest delivery vocabulary: **exactly-once *decision* (computation), at-least-once *delivery*, effectively-once *effect*.** Exactly-once delivery across a broker boundary is not achievable and is not claimed. Because the decision is singular and frozen, the at-least-once delivery the broker imposes can only redeliver the *same* decision.

## Why commit-then-publish — the directional argument

Both orderings leave a gap; they differ in which direction it points.

| Ordering | Residual gap | Recoverable? |
|---|---|---|
| **Publish-then-commit** | published-but-not-committed → **phantom**; recovery may recompute a *different* decision | No — the publish cannot be un-sent, and the only "already published" marker would be the failed commit |
| **Commit-then-publish** (this ADR) | committed-but-not-published → **undelivered** | Yes — the committed row is the durable record of "still needs publishing"; the relay replays it |

Commit-first deliberately trades the dangerous direction for the safe one and makes the safe direction durable and queryable, so the relay guarantees eventual delivery. In every recovery case the relay re-reads the frozen decision, so recovery only ever yields byte-identical duplicates.

## Options considered

| Option | Verdict | Reason |
|---|---|---|
| Publish inside the transaction (publish-then-commit) | Rejected | Phantom + divergence on commit failure; holds the row lock across broker I/O |
| Publish-first, then write the row | Rejected | Same phantom/divergence; the timeout poller can act on the not-yet-written row |
| `@TransactionalEventListener(AFTER_COMMIT)` | Rejected | In-process, no redelivery; a crash between commit and the listener loses the event |
| Dedicated outgoing queue | Rejected | Sits downstream of the durability boundary; relocates the dual-write rather than closing it |
| XA / two-phase commit | Rejected | Reintroduces a global coordinator the architecture avoids; weak RabbitMQ XA support; cost dwarfs a one-column outbox |
| **Transactional outbox on the correlation row + SKIP LOCKED relay** | **Accepted** | Decision durable at commit; publish leaves the locked transaction; reuses the ADR-13 row-lock idiom; no new table |

`ChainedTransactionManager` was also dismissed: best-effort 1PC and deprecated, the second commit can
still fail after the first, leaving the same residual window.

## Emission ownership and the timeout poller

The decision is recorded by whichever component completes the row, the result handler (ADR-16) or the timeout poller (ADR-15), and the relay dispatches it identically either way, so emission depends on no single producer. The timeout poller and the relay never contend: the poller claims rows with a`PENDING` signal past its deadline, the relay claims rows with `ready_for_dispatch_at NOT NULL AND dispatched_at NULL`. A row matches one or the other, never both, and writing `ready_for_dispatch_at`is the handoff. Both use the `SKIP LOCKED` idiom (ADR-13 §Delivery & Concurrency Guarantees).

## Division of labour — producer guarantee and consumer idempotency

The producer guarantee (commit-first plus relay) and consumer idempotency are complementary halves of the effectively-once contract. The producer guarantees one decision, persisted and replayed verbatim, which makes every duplicate harmless. Consumer idempotency collapses at-least-once delivery to one effect, which makes every duplicate inert. Idempotency remains necessary because redelivery still occurs (ADR-13 §Delivery & Concurrency Guarantees); commit-first is what makes it *sufficient*, because dedup on any key converges on the single persisted decision rather than possibly suppressing a genuinely different one.

## Ingress / egress asymmetry

This ADR uses a transactional outbox at egress although ADR-13 inverts the intake queue at ingress rather than using one. The difference turns on where the intent originates. At ingress the intent *arrives as a message*, so the broker already makes it durable and the published-first artifact is a command that re-executes idempotently to the same effect. At egress the intent is *born inside a DB transaction* with no upstream message, and the published-first artifact would be a verdict that re-derivation can change. Inputs are safe to publish before commit; outputs are not. ADR-13 §Ingress Inversion states the same asymmetry from the ingress side.

## Compliance rationale

The persisted decision is the auditable basis for the action taken — the record GDPR Art. 22 explainability and AML/DORA record-keeping expect to be retained and reproducible. If the decision of record could drift from the decision enforced, the audit trail would describe a decision the customer never received. This is the same structural-property pattern as ADR-14's asymmetric-aggregation guarantee: here the property is *singularity of the decision*. (Engineering rationale for auditability, not a legal compliance assertion.)

## Solves / Doesn't solve / Trade-off

**Solves:** phantom `EnrollmentDecisionEvent` on commit failure; record/reality divergence; emission that depends on a single producer; broker I/O held under the row lock.

**Doesn't solve:** true exactly-once *delivery* (not achievable across the broker — consumers must be idempotent); post-decision reversal — a signal arriving after dispatch is out of scope and discarded(ADR-15, ADR-16).

**Trade-off:** emission latency is bounded below by the relay poll interval, immaterial for decisions delivered out of band within a 48h window (ADR-12); and two markers plus one `@Scheduled` relay to operate.

**Reversibility:** the relay is one `@Scheduled` component and the markers are two columns; migration to a dedicated outbox table or to CDC is a mechanical change behind the same invariant.

**Triggers to reconsider:** a dedicated outbox table or CDC if the decision payload should not linger on the hot row, if outbox retention needs an independent lifecycle, or if a multi-consumer fan-out emerges; an `AFTER_COMMIT` in-process nudge layered on the relay if emission latency becomes user-visible, with the relay retained as the durability backstop (adopted — see §Amendment); splitting the dispatch backstop back out of the single `EnrollmentSweepJob` onto its own tighter schedule if the eager after-commit trigger is ever removed, since the one-sweep merge assumes eager dispatch owns steady-state latency.

## Amendment (2026-07-02) — implementation decisions

The mechanism is implemented in the decision-engine (`DecisionDispatcher`, `EnrollmentRepository.claimUndispatched` / `markDispatched`, and the dispatch phase of `EnrollmentSweepJob`). Five decisions were fixed at implementation time; the invariant above is unchanged.

**One marker instead of two.** The sketched `ready_for_dispatch_at` is folded into `decided_at`: both would be written by the same UPDATE in the decide transaction and carry identical information. The outbox predicate is `decision_result IS NOT NULL AND dispatched_at IS NULL` (partial index `idx_enrollments_undispatched`); dispatch latency is `dispatched_at − decided_at`; the stuck-outbox alert is `dispatched_at IS NULL AND now() − decided_at > threshold`. §Emission ownership's claim-predicate wording is amended accordingly.

**Eager after-commit trigger, scheduled backstop.** The nudge anticipated under §Triggers to reconsider was adopted at implementation time: `finalizeDecision` registers an after-commit synchronization that dispatches the just-committed decision, so steady-state emission latency is milliseconds rather than a poll interval, and the scheduled backstop runs on a loose cadence purely to re-publish what the eager path drops. The rejection of `@TransactionalEventListener(AFTER_COMMIT)` as a *sole* mechanism stands — the nudge is safe only because the backstop re-claims anything it drops. Both triggers converge on one dispatch path; the `dispatched_at IS NULL` guard on the stamp makes their race benign (at most a byte-identical duplicate, same `decision_id`). With the eager path owning the steady state the backstop is a cold path, so `EnrollmentSweepIT` is its routine proof, not optional insurance.

**One scheduled sweep, not two jobs.** The dispatch backstop and the ADR-15 timeout poller are both periodic `SKIP LOCKED` batch drains with loose latency tolerance — the backstop *because* eager dispatch owns steady-state latency, the timeout sweep by nature. They are merged into one `@Scheduled` `EnrollmentSweepJob` with two ordered phases: finalize expired timeouts, then dispatch undispatched decisions. The merge is at the schedule only — each phase keeps its own `@Transactional` per-batch boundary and is contained independently, so a broker outage in the dispatch phase cannot roll back or block timeout finalization, and vice versa. Timeouts run first so a row decided this tick is backstopped the same tick. One cadence (`decision-engine.sweep.interval`, default 10s — the tighter timeout requirement) drives both. This consolidation depends on eager dispatch existing: **if the eager trigger were ever removed, the dispatch phase would become latency-sensitive again and should be split back onto its own tighter schedule** (add to §Triggers to reconsider).

**Two claim queries, not one — even inside the single job.** Merging the jobs did not merge the queries, and deliberately so. The timeout claim (`decision_result IS NULL AND timeout_at <= now`) and the dispatch claim (`decision_result IS NOT NULL AND dispatched_at IS NULL`) select *disjoint* row populations — a row is undecided or decided, never both — so there is no per-row check to collapse; the two claims already scan different rows through different indexes. Folding them into one `OR` claim was considered and rejected on three grounds: (1) a single `SELECT ... FOR UPDATE` locks every claimed row in one transaction, which would force DB-only timeout finalization and network-bound publishing (a RabbitMQ round-trip plus confirm) into the same transaction, re-coupling the failure domains the two-phase split keeps apart — a broker stall would then block or roll back timeout finalization; (2) the phases ride two purpose-built partial indexes (`idx_enrollments_timeout_undecided` on `timeout_at`, `idx_enrollments_undispatched` on `decided_at`) and sort differently (oldest-deadline vs oldest-decided), which one query cannot serve cleanly; (3) a shared `LIMIT` would let one phase's backlog starve the other. "One job" is therefore a scheduling decision only — the claim queries and their transactions stay separate for correctness, exactly as they would under two jobs. This is also what makes the single-job/two-job choice reversible: splitting the schedule back apart touches only the `@Scheduled` wiring, not the queries.

**Dedup key: `decisionId`; the correlation id never leaves the service.** The event payload embeds `EnrollmentSnapshot` (ADR-06) — the intake data *without* `enrollmentId` — closing the gap where the correlation primary key leaked downstream inside `originalRequest` while the documentation claimed it was never published. Consumer idempotency keys on `decisionId`: it is frozen in the decide transaction, so every redelivery of a decision carries the same id, and dedup on it converges on the single persisted decision (§Division of labour). This also resolves the contradiction between the delivery-guarantees table's "idempotent receiver on `enrollmentId`" and the schema's "never published downstream". Regression pins: `EventSerializationTest.enrollmentDecisionEvent_json_carriesNoEnrollmentId` and `DecisionEventMapperTest.eventJson_neverContainsTheCorrelationEnrollmentId` assert the wire payload; `EnrollmentSweepIT.dispatchPhase_publishesThePersistedDecision_sameDecisionId_thenStamps` pins no-recompute.

## Related ADRs

- **ADR-13 — Messaging Architecture.** The correlation-record locking model, the delivery-guarantees
contract, and the ingress side of the commit-then-publish asymmetry; the correlation record doubles as the outbox.
- **ADR-16 — Concurrent Scatter-Gather Completion.** The result handler, one of the two deciders that records the decision this ADR emits.
- **ADR-15 — Timeout Policy.** The timeout poller, the other decider; shares the `SKIP LOCKED` idiom.
- **ADR-14 — Signal Classification Model.** Produces the `DecisionResult` this ADR freezes.
- **ADR-06 — Event Contract.** `EnrollmentDecisionEvent` schema and the idempotent-consumer requirement.
- **ADR-07 — Separate Scoring Microservice.** The event-driven boundary this ADR makes durable at the exit.
