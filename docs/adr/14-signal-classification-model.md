# ADR-14: Signal Classification Model

**Status:** Accepted

**Date:** June 2026

---

## Context

The Decision Engine receives results from multiple signal services and aggregates them into a single decision. Signals differ across two orthogonal properties: what should happen when a result is absent because the service timed out or failed, and whether the result can drive the final decision or can only contribute a routing annotation.

Without a unified model these semantics are encoded as per-signal conditional logic inside the aggregation. Every new signal adds a branch, the guarantee that an advisory signal cannot reject becomes a matter of convention rather than enforced structure, and the reasoning for each signal's behaviour is scattered rather than declared on the signal.

Two categories of signal exist. **Check-style** signals perform a discrete verification and produce a verdict: passed, explicitly failed, or no result. **Score-style** signals measure a value andplace it on an ordered risk scale.

---

## Decision

Every signal carries a classification as metadata, and the aggregation dispatches on that classification rather than on signal identity.

### Classifications

Three classifications cover the meaningful combinations of missing-signal behaviour and authority over the outcome. A third property rides along with them — the shape of the result a signal produces — and is stated here rather than left implicit, because aggregation dispatches on the classification and then reads the field that shape implies:

| Classification   | Missing-signal behaviour                          | Authority over outcome                                 | Result shape                    |
|------------------|---------------------------------------------------|--------------------------------------------------------|---------------------------------|
| `REQUIRED`       | Fail-closed; settles as a failed verdict (ADR-15) | Authoritative — can drive any outcome                  | Check-style — a `CheckOutcome`  |
| `BEST_EFFORT`    | Fail-open; aggregation proceeds without it        | Authoritative — can drive any outcome                  | Check-style — a `CheckOutcome`  |
| `SCORING_SIGNAL` | Fail-open; aggregation proceeds without it        | Advisory — can flag for review, cannot drive rejection | Score-style — a `RiskLevel`     |

Result shape is not independently configurable. Reclassifying a signal without changing what its worker reports leaves the aggregation branch reading a field the state does not carry, and the signal contributes nothing — silently, since no branch matches. The pairing is currently held by there being one listener per signal, which is a convention rather than a constraint.

The fourth logical combination, advisory and blocking on missing, is incoherent: a signal that cannot drive an outcome but holds the decision until it responds contradicts its own definition. The taxonomy is complete with three values.

### Aggregation

Once every applicable signal has settled or failed, the engine aggregates on classification. Anauthoritative signal (`REQUIRED` or `BEST_EFFORT`) that explicitly failed drives rejection. An advisory signal (`SCORING_SIGNAL`) at elevated or extreme risk flags for conditional approval and nothing more. A signal that never answered or returned no result contributes nothing, so fail-open is expressed by omission in the aggregation itself; the timeout transition that produces those states is an explicit branch (ADR-15). The accumulator and resolution-order mechanics live in `decision-engine/design.md §Decision Engine`; settling a missing `REQUIRED` signal as failed on timeout is ADR-15.

### Asymmetric aggregation guarantee

The guarantee that advisory signals cannot drive rejection is enforced by control flow, not by the type system. The advisory branch can only set the review flag, and the rejection path is physically unreachable from it. A change proposing that a scoring signal drive rejection would require an explicit, visible edit to that branch. The guarantee is verifiable by a property-based test asserting that, for every risk level with a passing authoritative check, the outcome is never rejection.

This is the property the Geo-Scoring Business Analysis relies on: a misconfigured density threshold inflates the analyst review queue but cannot cause a wrongful rejection.

---

## Consequences

**Gains.** Aggregation complexity is linear in the number of classifications rather than the number of signals. Adding a signal means declaring its applicable routes and its classification, and the engine's behaviour follows. Fail-open is consistent across all non-authoritative signals with no per-signal special-casing.

**Costs.**

- `REQUIRED` has no current assignment. It is named in advance so that introducing the first fail-closed signal (sanctions screening, regulated KYC) does not touch every aggregation dispatch site.
- Signal state is a sealed hierarchy — one variant per way a signal can end, each carrying only the data it has — rather than one record with a field per result category. The flat form was accepted first and reversed: its fields were independently nullable, so a caller reading the wrong one got a silent null, and the combination "never ran" was indistinguishable from "ran without a value" at the point where the decision is published. The price is that the domain type is also the persisted format, so the JSONB column inherits the polymorphism and every hand-written serialisation of the signal map must supply the declared value type or the discriminator is silently omitted.
- The model distinguishes a service that did not respond from one that responded without a result. Each signal service must emit a settled no-result when it runs and cannot produce a value, rather than letting the timeout poller mark it failed. The distinction is meaningful for audit and incident analysis.

---

## Related ADRs

- **ADR-03 — Security Architecture.** Prerequisite-token validation sits outside this model and is handled synchronously at the entry point, never as a signal in the map.
- **ADR-15 — Timeout Policy.** Implements missing-signal handling for `BEST_EFFORT` and`SCORING_SIGNAL`, and the fail-closed escalation a future `REQUIRED` signal needs.
- **ADR-16 — Concurrent Scatter-Gather Completion.** The locking and idempotency within which the aggregation runs.
- **ADR-07 — Separate Scoring Microservice.** The classification is what makes a new signal service pluggable.
