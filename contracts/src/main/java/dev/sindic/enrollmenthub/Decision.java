package dev.sindic.enrollmenthub;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class Decision {
    //general
    enum DecisionResult {
        APPROVED, CONDITIONAL_APPROVED, REJECTED
    }

    /**
     * Outcome produced by a completed check-style signal.
     * Only meaningful when SignalProcessingState is SETTLED.
     * Null for score-style signals, which express outcome via RiskLevel.
     */
    enum SignalOutcome {
        OK,         // verification passed
        FAILED,     // verification did not pass
        NO_RESULT   // signal ran but could not produce a meaningful result
    }

    enum RiskLevel {
        LOW,
        MEDIUM,
        HIGH,
        EXTREME  // saturated / truncated — beyond the measurable range
    }

    // internal domain
    enum PaymentType {
        CREDIT_CARD, INVOICE
    }
    // the classificatio of a signal
    enum GateClassification {
        BEST_EFFORT, REQUIRED, SCORING_SIGNAL
    }
    // This is used to define which signals fall into which category
    enum SignalConfig {
        GEO_SCORE(Set.of(PaymentType.CREDIT_CARD),
                GateClassification.SCORING_SIGNAL),
        FRAUD_CHECK(Set.of(PaymentType.CREDIT_CARD, PaymentType.INVOICE),
                GateClassification.BEST_EFFORT);

        private final Set<PaymentType> applicableRoutes;
        private final GateClassification classification;

        SignalConfig(Set<PaymentType> applicableRoutes, GateClassification classification) {
            this.applicableRoutes = applicableRoutes;
            this.classification = classification;
        }
    }

    /**
     * Lifecycle state of a signal within the scatter-gather pipeline.
     * Tracks whether the signal is still in progress, has settled with
     * a result, or the processing itself failed (timeout, crash).
     */
    enum SignalProcessingState {
        PENDING,    // signal initiated, result not yet received
        SETTLED,    // signal completed and produced a result
        FAILED      // signal did not complete — timeout or service failure
    }

    // renamed check to signal, signal state carries an additional pending,
    // reason is used in case of failure, for example, like timeout, unavailable etc.
    record SignalState(SignalProcessingState processingState, SignalOutcome outcome, RiskLevel riskLevel, String reason){}

    record EnrollmentDecisionAggregate(
            UUID requestId,
            DecisionResult decisionResult,
            //map signal name to signalStatus
            Map<String, SignalState> signalStates,
            Instant decidedAt) {
    }

    // internal jpa entity - this is a sketch only
    public class EnrollmentEntity {

        // pk
        private UUID requestId;

        private UUID decisionId;

        private String originalRequest; //serialized json from the original request type

        private DecisionResult decisionResult;
        // the signalstate enum contains the name of the SignalConfig, not necessary to store the whole SignalConfig
        // signal name is the enum name of the signal config
        private Map<String, SignalState> signals;

        // --- Timestamps ---
        private Instant createdAt;
        private Instant timeoutAt;
        private Instant decidedAt;
    }

// external api response

    // the api response has only a satus and optional a value and reason (when failure happens)
    record EnrollmentSignal(SignalOutcome outcome, RiskLevel riskLevel, String reason) { }

    record EnrollmentDecisionEvent(
            String decisionId,
            // should be the plain json string, not escaped.
            String originalRequest,
            DecisionResult decisionResult,
            // maps signalName to signal
            Map<String, EnrollmentSignal> signals) { }

    // decision engine aggregate

}

