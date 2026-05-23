# ADR-015: Concurrent Scatter-Gather Completion — Transactional Safety and ACK Ordering

**Status:** Accepted
**Date:** April 2026

## Context

The scatter-gather pipeline (ADR-003) dispatches `EnrollmentAccepted` to multiple signal
services concurrently. For the CREDIT_CARD route, geo-scoring and fraud detection both
receive the event and execute in parallel. Their results — `GeoScoreResult` and
`FraudCheckResult` — can arrive at the decision-engine at nearly the same time.

The decision-engine must:

1. Record each result in the correlation row.
2. Evaluate the completion predicate after each result arrives.
3. Trigger the decision engine exactly once, when the last pending signal settles.

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

Both updates commit successfully. Both reads happen to see the other signal as still
pending (stale read between the two separate statements). Neither thread triggers the
decision engine. The enrollment hangs until the timeout poller fires.

The same failure mode occurs even with column-scoped updates. Column isolation prevents
data loss; it does not prevent the completion check from racing.

### Options evaluated

**Option A — `SELECT FOR UPDATE` row-level locking.**
Each event handler opens a transaction, acquires a `SELECT FOR UPDATE` lock on the
correlation row, writes its signal result, re-reads the now-locked row, evaluates the
completion predicate, and commits. The second handler to arrive blocks on the lock until
the first commits. When it proceeds, it reads the fully committed state of both signals
and correctly evaluates completion.

**Option B — Optimistic locking (`@Version` column).**
Each handler reads the row, increments a version column, and issues a conditional UPDATE.
On version conflict, the handler retries from the read. Correctly detects the race but
requires application-level retry logic. Under sustained concurrent load, retries
accumulate. The version column introduces an extra constraint for the timeout poller
(ADR-010) to manage as well.

**Option C — Single atomic UPDATE with completion flag.**
A single SQL statement updates the signal slot and sets a `is_complete` flag computed
inline (e.g., a CASE expression over all signal slots). The flag becomes the trigger.
Requires computing the predicate in SQL rather than in the domain model; the logic is
split between the application and the database. Harder to evolve as new signals are added
(ADR-003 extensibility seam).

## Decision

**Option A — `SELECT FOR UPDATE` row-level locking.**

Each result handler acquires a pessimistic row lock, writes its signal result (with
idempotency check), evaluates the completion predicate, and if complete invokes the
decision engine and publishes `EnrollmentDecisionEvent` — all within a single transaction.
The RabbitMQ ACK is issued only after the transaction commits.

**Solves:** TOCTOU race between concurrent result handlers. Exactly-once decision trigger.
Safe redelivery via idempotency guard. No retry loop in application code.

**Doesn't solve:** Duplicate `EnrollmentDecisionEvent` events if the process crashes
between COMMIT and ACK (the at-least-once delivery window). Downstream consumers of
`EnrollmentDecisionEvent` must be idempotent. This is consistent with the event-driven
contract established in ADR-003 and ADR-009.

**Trade-off:** Row-level contention on the correlation record during concurrent arrivals.
At ≤5 RPS peak (§1.4) and two concurrent signals per CREDIT_CARD request, lock wait time
is negligible. Contention becomes relevant at ≥50 RPS sustained; at that point revisit
Option C or a CAS-based approach.

**Simplicity gate:** Option B requires retry logic and a version column managed by two
independent writers (result handlers and the timeout poller). Option C moves predicate
logic into SQL and tightly couples the schema to the decision rules. Option A keeps all
logic in the domain model and adds no retry complexity — the lock wait is the retry.

**Reversibility:** Straightforward — `SELECT FOR UPDATE` can be replaced with optimistic
locking independently of the completion predicate or decision engine.

## ACK-after-commit and at-least-once delivery

RabbitMQ consumer acknowledgement is manual — the ACK is issued only after the database
transaction commits. A crash between COMMIT and ACK causes redelivery; the idempotency
guard absorbs the duplicate without retriggering the decision engine. No distributed
transaction is required: the database is the source of truth, and at-least-once delivery
with idempotent consumers is the contract.

## Idempotency guard

Each result handler checks the current state of the target signal slot in the JSONB
`signals` map before writing. If the slot is absent from the map (signal not applicable
to this route) or already settled, the handler returns without mutation. A `false` return
indicates the signal has already been recorded — duplicate delivery or late arrival after
timeout. The handler commits without triggering the decision engine.

## Write path — explicit `UPDATE` for the JSON-mapped column

The signal map is a `Map<SignalConfig, SignalState>` mapped to a PostgreSQL `jsonb`
column via `@JdbcTypeCode(SqlTypes.JSON)`. Two ways to persist a mutation are available:

1. **Dirty-tracked in-place mutation.** Load the entity, mutate the map field in
   memory, rely on Hibernate's flush-time dirty-checker to compare against a
   snapshot taken at load and issue an `UPDATE` if they differ.
2. **Explicit `UPDATE`.** Compute the new value in application memory, issue a JPA
   `UPDATE` statement that writes the column literally, assert the returned row count.

**Decision:** option 2 for JSON-mapped collection columns. Scalar columns continue
to use option 1 (dirty-tracking on scalar fields is stable across Hibernate versions
and explicitly part of the JPA spec).

**Why not dirty-tracking on the JSON column.** Option 1 works empirically today, but
the mechanism that makes it work is *below* the JPA spec. Hibernate snapshots the
field at load via a `MutabilityPlan` / `JavaType` resolved through its type-contributor
chain. For a `Map<…, …>` field with `@JdbcTypeCode(SqlTypes.JSON)` and Jackson on the
classpath, the resolved plan happens to deep-copy, so mutation is detectable at flush.
The resolution is **not** part of the JPA contract and can shift across Hibernate
minor versions, across changes in the type-contributor wiring, and across small
refactors of the field's declared static type. A shift to a shallow-copying plan
would silently break persistence: no exception, no log, just an `UPDATE` that never
fires and stale reads thereafter. Scalar columns do not share this risk because their
`MutabilityPlan` is stable and well-tested by the JPA TCK.

**Solves:** silent persistence drift across Hibernate upgrades on the JSON column.
Failure modes become loud (row-count assertion throws on unexpected outcomes).

**Trade-off:** one dedicated repository method per JSON-collection write path; the
entity loses its in-place mutator for those columns and becomes a read-only
projection on the JSON side. The mutation is one extra explicit call instead of a
hidden side-effect of `signals.put(...)` at flush — which is the property the
decision optimises for.

**Reversibility:** per call-site. Drop the repository `@Modifying` method, restore
the entity's in-place mutator. Trigger to revisit: Hibernate evolving its
`MutabilityPlan` guarantees for JSON-mapped collections into a stable, spec-level
commitment.

The JPA implementation idiom — the `@Modifying` annotation shape, the JSONB cast,
the row-count assertion pattern, the regression test that pins the runtime behaviour
— lives in `decision-engine/design.md` §"Signal-map persistence pattern".

## Consequences

**Gains:** Completion predicate evaluated once, by the handler that observes the final
state. Decision engine triggered exactly once per request under concurrent arrivals.
Redelivery is safe without application-level deduplication tables. Consistent with the
existing `SELECT FOR UPDATE` pattern already established for the timeout poller (ADR-010).

**Loses:** Each result handler holds a row lock for the duration of its transaction.
Under the current volume envelope this is negligible. A pathological slow DB write or
network partition during a locked transaction delays all concurrent handlers for that
`enrollmentId`.
