# ADR-16: Concurrent Scatter-Gather Completion — Transactional Safety and ACK Ordering

**Status:** Accepted

**Date:** June 2026

## Context

The decision engine's scatter-gather (ADR-13) dispatches a per-signal command to each applicable signal service concurrently. For the CREDIT_CARD route, geo-scoring and fraud detection each receive their command and execute in parallel. Their results — `GeoScoreResult` and`FraudCheckResult` — can arrive at the decision-engine at nearly the same time.

The decision-engine must:

1. Record each result in the correlation row.
2. Evaluate the completion predicate after each result arrives.
3. Compute and record the decision exactly once, when the last pending signal settles (emission
   is owned by ADR-17).

### The race condition

A naive read-modify-write approach produces a TOCTOU (time-of-check/time-of-use) race:

```
Thread A (geo-score)                      Thread B (fraud)
  read correlation row (signals JSONB)      read correlation row (signals JSONB)
  → FRAUD_CHECK slot = PENDING              → GEO_SCORE slot = PENDING
  UPDATE signals: GEO_SCORE = SETTLED      UPDATE signals: FRAUD_CHECK = SETTLED
  evaluate: FRAUD_CHECK settled? → no       evaluate: GEO_SCORE settled? → no
  → not complete, do nothing                → not complete, do nothing
```

Both updates commit successfully. Both reads happen to see the other signal as still pending (stale read between the two separate statements). Neither thread observes completion. The enrollment hangs until the timeout poller fires.

The same failure mode occurs even with column-scoped updates. Column isolation prevents data loss; it does not prevent the completion check from racing.

### Options evaluated

**Option A — `SELECT FOR UPDATE` row-level locking.**
Each handler acquires a pessimistic row lock (WAIT variant — ADR-13 §"Delivery & Concurrency Guarantees", writes its signal result, re-reads the locked row, evaluates the completion predicate(decision-engine/design.md §"Correlation Record Domain Model"), and commits. The waiting handler observes the first handler's committed signal before it evaluates, which is what closes the TOCTOU race.

**Option B — Optimistic locking (`@Version` column).**
Each handler reads the row, increments a version column, and issues a conditional UPDATE. On version conflict, the handler retries from the read. Correctly detects the race but requires application-level retry logic. Under sustained concurrent load, retries accumulate. The version column introduces an extra constraint for the timeout poller(ADR-15) to manage as well.

**Option C — Single atomic UPDATE with completion flag.**
A single SQL statement updates the signal slot and sets a `is_complete` flag computed inline (e.g., a CASE expression over all signal slots). The flag becomes the trigger. Requires computing the predicate in SQL rather than in the domain model; the logic is split between the application and the database. Harder to evolve as new signals are added (ADR-13 extensibility seam).

## Decision

**Option A — `SELECT FOR UPDATE` row-level locking.**

Each result handler acquires a pessimistic row lock, writes its signal result (with
idempotency check), evaluates the completion predicate, and if complete computes the decision
(ADR-14) and records it on the correlation row for dispatch (ADR-17) — all within a single
transaction. The inbound result message is ACKed only after the transaction commits, so a crash
before the ACK redelivers the result rather than losing it.

**Solves:** TOCTOU race between concurrent result handlers. Exactly-once decision *computation* — exactly one handler observes the final settled state and computes the decision. Safe redelivery of inbound results via the idempotency guard. No retry loop in application code.

**Doesn't solve:** Emission of the decision. How the recorded decision is published durably, and the at-least-once delivery of `EnrollmentDecisionEvent` to idempotent downstream consumers, are owned by ADR-17. This ADR's responsibility ends once exactly one handler has computed and recorded the decision on the row.

**Trade-off:** Row-level contention on the correlation record during concurrent arrivals. At ≤5 RPS peak (§1.4) and two concurrent signals per CREDIT_CARD request, lock wait time is negligible. Contention becomes relevant at ≥50 RPS sustained (ADR-13); at that point revisit Option C or a CAS-based approach.

**Simplicity gate:** Option B requires retry logic and a version column managed by two independent writers (result handlers and the timeout poller). Option C moves predicate logic into SQL and tightly couples the schema to the decision rules. Option A keeps all logic in the domain model and adds no retry complexity — the lock wait is the retry.

**Reversibility:** Straightforward — `SELECT FOR UPDATE` can be replaced with optimistic locking independently of the completion predicate or decision computation.

## Inbound-result ACK after commit

The inbound result message (`GeoScoreResult` / `FraudCheckResult`) is acknowledged manually, only after the database transaction commits. A crash between COMMIT and ACK redelivers the result, and the idempotency guard absorbs it without re-recording the signal or recomputing the decision. No distributed transaction is required: the correlation database is the source of truth, and at-least-once delivery with an idempotent handler is the contract on this side (ADR-13 §"Delivery & Concurrency Guarantees"). Durable emission of the decision event is the symmetric concern on the outbound side, owned by ADR-17.

## Idempotency guard

Each result handler checks the current state of the target signal slot in the JSONB`signals` map before writing. If the slot is absent from the map (signal not applicable to this route — applicability per decision-engine/design.md §"Correlation Record Domain Model") or already settled, the handler returns without mutation. A `false` return indicates the signal has already been recorded — duplicate delivery or late arrival after timeout. The handler returns without re-recording the signal or recomputing the decision.

## Write path — explicit `UPDATE` for the JSON-mapped column

The signal map is a `Map<SignalConfig, SignalState>` persisted to a PostgreSQL `jsonb` column via`@JdbcTypeCode(SqlTypes.JSON)`. It is written with an explicit JPA `UPDATE` that asserts the returned row count, rather than by in-place mutation relying on Hibernate's flush-time dirty-checker. Scalar columns keep using dirty-tracking, which is stable and part of the JPA spec.

**Why not dirty-tracking on the JSON column.** It works empirically today, but the mechanism is*below* the JPA spec: whether a mutation is detected depends on the `MutabilityPlan` Hibernate resolves for the field, which happens to deep-copy for this type today but can shift across Hibernate versions, type-contributor wiring, or a refactor of the field's declared type. A shift to a shallow-copying plan would break persistence silently — no exception, just an `UPDATE` that never fires and stale reads after. Scalar columns do not share this risk.

**Solves:** silent persistence drift across Hibernate upgrades on the JSON column. Failure modes become loud (row-count assertion throws on unexpected outcomes).

**Trade-off:** one dedicated repository method per JSON-collection write path; the entity loses its in-place mutator for those columns and becomes a read-only projection on the JSON side. The mutation is one extra explicit call instead of a hidden side-effect of `signals.put(...)` at flush — which is the property the decision optimises for.

**Reversibility:** per call-site. Drop the repository `@Modifying` method, restore the entity's in-place mutator. Trigger to revisit: Hibernate evolving its`MutabilityPlan` guarantees for JSON-mapped collections into a stable, spec-level commitment.

The JPA implementation idiom — the `@Modifying` annotation shape, the JSONB cast, the row-count assertion pattern, the regression test that pins the runtime behaviour — lives in `decision-engine/design.md` §"Signal-map persistence pattern".

## Consequences

The completion predicate is evaluated once, by the handler that observes the final state, and the decision is computed and recorded exactly once under concurrent arrivals, without an application-level deduplication table. The cost is that each handler holds the row lock for its transaction, so a pathologically slow write or a network partition under the lock delays the other handlers for that `enrollmentId`. Both are negligible at the current volume.
