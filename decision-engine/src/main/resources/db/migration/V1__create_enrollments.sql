-- Correlation table for the scatter-gather decision-engine pipeline.
-- Each row tracks one enrollment request through its lifecycle.

CREATE SCHEMA IF NOT EXISTS enrollment_hub;

CREATE TABLE enrollment_hub.enrollments (

    -- Correlation identity (internal PK). Used as the correlation key across the
    -- scatter-gather — the intake event, the per-signal check commands, and their
    -- results all carry it — and returned to the caller in the 202. Withheld only
    -- from the outbound EnrollmentDecisionEvent, which publishes a fresh decision_id
    -- instead (see decision_id below, ADR-17).
    enrollment_id           UUID            PRIMARY KEY,
    payment_type            VARCHAR(20)     NOT NULL,

    -- Full original enrollment request. The engine has no other source for the payload the
    -- decision event carries downstream — the intake message is long acked and ADR-02 rules out
    -- asking the Account Service — so the row is where it lives, from intake until the publisher
    -- confirm returns. Nullable because that hold ends there: once dispatched_at is stamped
    -- the payload has no reader (claimUndispatched selects dispatched_at IS NULL and
    -- markDispatched is guarded on it, so a stamped row is never re-claimed), and
    -- PayloadRetentionJob erases it on the next pass (ADR-20).
    -- The invariant that replaces NOT NULL is narrower and enforced by that predicate rather
    -- than by the schema: a row with dispatched_at IS NULL always carries its payload.
    original_request        JSONB,

    -- Signal state map (JSONB)
    -- Map keyed by SignalConfig name (e.g. 'GEO_SCORE', 'FRAUD_CHECK').
    -- Only signals applicable to the route are present; absence = not applicable.
    -- Each value is a tagged SignalState variant carrying a "kind" discriminator:
    --   {"kind":"PENDING"} | {"kind":"CHECKED","outcome":"OK"} | {"kind":"SCORED","riskLevel":"HIGH"}
    --   {"kind":"NO_RESULT","reason":"..."} | {"kind":"NOT_EXECUTED","reason":"timeout"}
    -- The column is opaque to every application query; only ad-hoc and operational
    -- queries read inside it, and they match on "kind" (see the GIN index below).
    signals                 JSONB           NOT NULL,

    -- Intake idempotency ledger (ADR-13 §Ingress Inversion & Consumer-Side State Machine).
    -- PENDING at insert; COMPLETED once the per-signal check commands have been dispatched.
    -- A redelivered intake message found COMPLETED is acked without re-dispatching.
    intake_status           VARCHAR(20)     NOT NULL DEFAULT 'PENDING',

    -- Decision (nullable — set when all signals settle)
    -- decision_id is a fresh UUID generated at decision time, published instead of enrollment_id.
    decision_result         VARCHAR(30),
    decision_id             UUID,

    -- Timestamps
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    timeout_at              TIMESTAMPTZ     NOT NULL,
    decided_at              TIMESTAMPTZ,

    -- Outbox marker (ADR-17): stamped only after the publisher confirm for the
    -- EnrollmentDecisionEvent returns. decision_result NOT NULL + dispatched_at NULL
    -- = decided, awaiting delivery — the dispatch relay's claim predicate.
    dispatched_at           TIMESTAMPTZ
);

-- Index for the Timeout Poller
CREATE INDEX idx_enrollments_timeout_undecided
    ON enrollment_hub.enrollments (timeout_at)
    WHERE decision_result IS NULL;

-- GIN index for efficient querying inside the signals JSONB column
CREATE INDEX idx_enrollments_signals_jsonb
    ON enrollment_hub.enrollments USING GIN (signals);

-- Partial index for the decision dispatch relay (ADR-17): decided-but-undispatched rows only,
-- so the steady-state relay tick is a probe against an (almost always empty) index.
CREATE INDEX idx_enrollments_undispatched
    ON enrollment_hub.enrollments (decided_at)
    WHERE decision_result IS NOT NULL AND dispatched_at IS NULL;

-- Partial index over the rows that still hold an enrollment payload (ADR-20). Serves both the
-- retention claim (ordered by dispatched_at) and the held-payload age gauge, and shrinks toward
-- empty in steady state — same shape, and same reason, as idx_enrollments_undispatched.
CREATE INDEX idx_enrollments_payload_held
    ON enrollment_hub.enrollments (dispatched_at)
    WHERE original_request IS NOT NULL;
