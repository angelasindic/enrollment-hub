package dev.sindic.enrollmenthub.decisionengine.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Every asynchronous signal in the scatter-gather pipeline, each declaring the payment routes it
 * applies to and its {@link GateClassification} (ADR-14). Prerequisites (payment token, eIDAS)
 * are resolved synchronously before intake and are not signals (ADR-18, ADR-19).
 *
 * <p>Single source of applicability: the same route metadata seeds the dispatch set
 * ({@link #applicableSignals}) and the gather set ({@link #initializeFor}), so the two cannot
 * drift. Inapplicability is expressed by absence from the signal map, never by a sentinel state.
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

    /** The signals applicable to the given route, in enum-declaration order. */
    public static Set<SignalConfig> applicableSignals(PaymentType paymentType) {
        var applicable = EnumSet.noneOf(SignalConfig.class);
        for (var sc : values()) {
            if (sc.applicableTo(paymentType)) {
                applicable.add(sc);
            }
        }
        return applicable;
    }

    public GateClassification classification() {
        return classification;
    }

    /** The initial signal map for the given route — every applicable signal {@code Pending}. */
    public static Map<SignalConfig, SignalState> initializeFor(PaymentType paymentType) {
        var signals = new EnumMap<SignalConfig, SignalState>(SignalConfig.class);
        for (var sc : values()) {
            if (sc.applicableTo(paymentType)) {
                signals.put(sc, new SignalState.Pending());
            }
        }
        return signals;
    }

    /** The completion predicate: every signal present has reached a terminal state. */
    public static boolean allSettled(Map<SignalConfig, SignalState> signals) {
        return signals.values().stream().allMatch(SignalState::hasSettled);
    }
}
