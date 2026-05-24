# ADR-016 — Decision Engine Signal Classification Model

**Status:** Accepted
**Related ADRs:** ADR-002, ADR-007, ADR-010, ADR-015

---

## Context

The Decision Engine receives results from multiple signal services and aggregates them
into a single decision event published to downstream consumers. Different signals have
fundamentally different semantics across two orthogonal properties: what should happen
when a signal's result is absent (because the service timed out or failed), and whether
the signal's result can drive the final decision or can only contribute a routing
annotation.

Without a unified model these semantics are encoded as per-signal conditional logic
inside the aggregation. Every new signal adds a branch, the asymmetric aggregation
guarantee becomes a matter of convention rather than enforced structure, and the
reasoning for each signal's behaviour is scattered rather than declared on the signal
itself.

Two distinct categories of signal have been identified:

- **Check-style signals** perform a discrete verification and produce a verdict — the
  verification either passed, explicitly failed, or could not produce a result.
- **Score-style signals** measure a value and place it on an ordered risk scale,
  reflecting where a measurement falls relative to configured thresholds.

---

## Decision

A classification is assigned as metadata on every signal participating in the
scatter-gather pipeline. The Decision Engine's aggregation dispatches on classification
rather than on specific signal identity.

### Classifications

Three classifications cover the meaningful combinations of two orthogonal properties —
missing-signal behaviour and authority over the outcome:

| Classification    | Missing-signal behaviour                            | Authority over outcome                                 |
|-------------------|-----------------------------------------------------|--------------------------------------------------------|
| `REQUIRED`        | Blocks completion; escalation via ADR-010           | Authoritative — can drive any outcome                  |
| `BEST_EFFORT`     | Fail-open; aggregation proceeds without the signal  | Authoritative — can drive any outcome                  |
| `SCORING_SIGNAL`  | Fail-open; aggregation proceeds without the signal  | Advisory — can flag for review, cannot drive rejection |

The fourth logical combination — advisory and blocking on missing — is incoherent: a
signal that cannot drive an outcome but blocks the decision until it responds contradicts
its own definition. The taxonomy is complete with three values.

### Signal categories

Check-style signals (`BEST_EFFORT`, `REQUIRED`) produce a discrete outcome: passed,
explicitly failed, or no result. Score-style signals (`SCORING_SIGNAL`) produce a risk
level on an ordered scale. The two categories use distinct result types so the
aggregation can read the correct field for any given signal based on its classification.

Both the processing lifecycle (did the signal run, is it pending, did it fail to
complete?) and the signal result (what did it find?) are carried together on the signal
state. This separation is what allows fail-open behaviour to be expressed by omission
rather than by explicit branches.

### Aggregation

The aggregation runs once all applicable signals have settled or failed. It accumulates
two flags — one for rejection, one for conditional approval — and resolves them in
priority order: rejection overrides conditional approval, which overrides approval.

- An authoritative signal (`BEST_EFFORT` or `REQUIRED`) that explicitly failed drives
  rejection. A timeout or no-result fails open and contributes nothing.
- An advisory signal (`SCORING_SIGNAL`) at elevated or extreme risk flags for conditional
  approval. A timeout fails open. It cannot drive rejection under any circumstance.
- `REQUIRED` signals are guaranteed to be settled by the time aggregation runs — the
  timeout escalation policy (ADR-010) fires before the completion predicate releases.

Fail-open behaviour is implicit in the loop structure: signals that did not settle
contribute nothing to either accumulator. No explicit fail-open branch is required.

### Asymmetric aggregation guarantee

The guarantee that advisory signals cannot drive rejection is enforced by control flow,
not by the type system. The advisory branch can only set the review flag; the rejection
accumulator is physically unreachable from that branch. A future change proposing that a
scoring signal should drive rejection would require an explicit, visible change to that
branch — one the classification comment would flag as a contract violation.

This is the property the Geo-Scoring Business Analysis relies on: misconfiguring a
density threshold inflates the analyst review queue but cannot cause wrongful rejection.

---

## Consequences

### Positive

Aggregation complexity is linear in the number of classifications, not in the number of
signals. Adding a new signal requires only declaring its applicable routes and its
classification; the engine's behaviour follows automatically.

The two signal categories use distinct result types without requiring a type hierarchy.
The flat state record serialises trivially to the database without type discriminators.

Fail-open behaviour is implicit and consistent across all non-authoritative signals.
There is no special-casing per signal.

### Negative / Trade-offs

`REQUIRED` has no current assignment. The classification is named in advance to avoid a
future expansion that touches every aggregation dispatch site when the first fail-closed
signal (e.g. sanctions screening, regulated KYC) is introduced.

The two nullable result fields — one for check-style, one for score-style — mean only
one is meaningful per signal entry. A caller who reads the wrong field gets a null
silently rather than a compile-time error. This is accepted as a reasonable trade-off
against the complexity of a sealed type hierarchy for a two-signal system.

The distinction between a service that did not respond (timeout/crash) and a service
that responded but could not produce a result requires discipline in each signal service.
Services must emit a settled no-result response when they run and cannot score, rather
than letting the timeout poller treat them as failed. The difference is meaningful for
audit and incident analysis.

---

## Compliance and Verification

The asymmetric guarantee — that advisory signals cannot drive rejection — is verifiable
by inspection of the aggregation loop and by an explicit property-based test asserting
that for every possible risk level, with a passing authoritative check, the outcome is
never rejection.

Adding a new signal must require its route applicability and classification to be
declared as mandatory constructor arguments, so omitting either is a compile-time error.

The distinction between a timed-out signal and a signal that settled without a result
must be covered by unit tests on each signal service.

---

## Related ADRs

- **ADR-002** — Separate Scoring Microservice. The classification model makes signal
  services pluggable: a new service declares a signal with a classification and the
  aggregation behaviour follows.
- **ADR-007** — Security Architecture: Prerequisite Tokens. Prerequisite validation is
  outside this classification model and governed by ADR-007's synchronous entry-point
  flow.
- **ADR-010** — Timeout Policy. Implements missing-signal handling for `BEST_EFFORT` and
  `SCORING_SIGNAL` via the timeout poller. Requires extension when the first `REQUIRED`
  signal is introduced.
- **ADR-015** — Concurrent Scatter-Gather Completion. The locking and idempotency
  guarantees within which the aggregation loop operates.
