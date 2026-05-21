-- Correlation table for the scatter-gather decision-engine pipeline.
-- Each row tracks one enrollment request through its lifecycle.
-- See ADR-016 for concurrent completion safety (SELECT FOR UPDATE + idempotency guard).
-- See ADR-018 for the Signal Classification Model implemented here.

CREATE SCHEMA IF NOT EXISTS enrollment_hub;

CREATE TABLE enrollment_hub.enrollments (

    -- Correlation identity (internal PK — never published downstream)
    request_id              UUID            PRIMARY KEY,
    payment_type            VARCHAR(20)     NOT NULL,

    -- Full original enrollment request, stored at intake so the decision event
    -- can carry it to downstream consumers without a separate lookup.
    original_request        JSONB           NOT NULL,

    -- Signal state map (JSONB)
    -- Map keyed by SignalConfig name (e.g. 'GEO_SCORE', 'FRAUD_CHECK').
    -- Only signals applicable to the route are present; absence = not applicable.
    signals                 JSONB           NOT NULL,

    -- Decision (nullable — set when all signals settle)
    -- decision_id is a fresh UUID generated at decision time, published instead of request_id.
    decision_result         VARCHAR(30),
    decision_id             UUID,

    -- Timestamps
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    timeout_at              TIMESTAMPTZ     NOT NULL,
    decided_at              TIMESTAMPTZ
);

-- Index for the Timeout Poller
CREATE INDEX idx_enrollments_timeout_undecided
    ON enrollment_hub.enrollments (timeout_at)
    WHERE decision_result IS NULL;

-- GIN index for efficient querying inside the signals JSONB column
CREATE INDEX idx_enrollments_signals_jsonb
    ON enrollment_hub.enrollments USING GIN (signals);
