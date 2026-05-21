package dev.sindic.enrollmenthub.decisionengine.domain;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Every asynchronous signal participating in the scatter-gather pipeline.
 *
 * <p>Each value declares the payment routes on which it applies and its
 * {@link GateClassification}. Prerequisites (PAYMENT_TOKEN, eIDAS) are
 * resolved synchronously before the correlation record is created and are
 * not represented here.
 *
 * <p>Only signals applicable to the current payment route are initialised in
 * the correlation record's signal map. Inapplicability is expressed by absence
 * from the map, not by a sentinel value in {@link SignalProcessingState}.
 *
 * @see GateClassification
 * @see SignalState
 */
public enum SignalConfig {

    GEO_SCORE(
            Set.of(PaymentType.CREDIT_CARD),
            GateClassification.SCORING_SIGNAL
    ),
    FRAUD_CHECK(
            Set.of(PaymentType.CREDIT_CARD, PaymentType.INVOICE),
            GateClassification.BEST_EFFORT
    );

    private final Set<PaymentType> applicableRoutes;
    private final GateClassification classification;

    SignalConfig(Set<PaymentType> applicableRoutes, GateClassification classification) {
        this.applicableRoutes = applicableRoutes;
        this.classification = classification;
    }

    public boolean applicableTo(PaymentType paymentType) {
        return applicableRoutes.contains(paymentType);
    }

    public GateClassification classification() {
        return classification;
    }

    /**
     * Builds the initial signal map for the given payment-type route.
     * Only applicable signals are included; absent entries are not applicable.
     */
    public static Map<SignalConfig, SignalState> initializeFor(PaymentType paymentType) {
        var signals = new EnumMap<SignalConfig, SignalState>(SignalConfig.class);
        for (var sc : values()) {
            if (sc.applicableTo(paymentType)) {
                signals.put(sc, SignalState.pending());
            }
        }
        return signals;
    }

    /**
     * Returns {@code true} when every signal in the map has reached a terminal state.
     */
    public static boolean allSettled(Map<SignalConfig, SignalState> signals) {
        return signals.values().stream().allMatch(SignalState::hasSettled);
    }
}
