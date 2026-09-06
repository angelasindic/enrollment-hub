package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.decisionengine.domain.SignalConfig;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Counts how every signal settled, per decision — the observable side of the ADR-15 fail-open
 * policy.
 *
 * <p>Fail-open is silent by construction: a signal that never answered or produced no value
 * contributes nothing to the aggregation ({@link
 * dev.sindic.enrollmenthub.decisionengine.domain.DecisionEngine} — fail-open is by omission), so a
 * detection service can be wholly unavailable while every enrollment is still decided and
 * dispatched normally. Nothing else in the service would show it: a timed-out signal leaves only
 * the poller's batch-count log, and a signal that ran and returned no result leaves not even that.
 * This counter makes the degradation visible, and the {@code SignalFailingOpen} rule in
 * {@code monitoring/prometheus/rules} makes it noticed.
 *
 * <p>Every terminal state is counted, not only the two fail-open ones, so the alert can express
 * fail-open as a <em>proportion</em> of settlements rather than a raw count — an absolute
 * threshold would mean something different at 5 RPS than at 500. All series are registered at
 * startup so the ratio's denominator exists before the first decision; an absent series and a
 * zero one are not the same thing to {@code increase()}.
 *
 * <p>Recorded once per decision, at the finalize step both completion paths converge on, after the
 * {@code decision_result IS NULL} guard has admitted the write. A transaction that rolled back
 * after passing that guard would over-count; that is accepted rather than deferred to
 * {@code afterCommit}, because the alert reads a ratio over a 30-minute window and a second
 * transaction synchronization would cost more comprehension than the precision is worth.
 */
@Component
class SignalSettlementMetrics {

    static final String SETTLED_METRIC = "decisionengine.signal.settled";

    /** Terminal state tags — the {@link SignalState} discriminators minus the unreachable {@code PENDING}. */
    private static final List<String> TERMINAL_STATES =
            List.of("CHECKED", "SCORED", "NO_RESULT", "NOT_EXECUTED");

    private final Map<SignalConfig, Map<String, Counter>> counters = new EnumMap<>(SignalConfig.class);

    SignalSettlementMetrics(MeterRegistry registry) {
        for (var signal : SignalConfig.values()) {
            var byState = new HashMap<String, Counter>();
            for (var state : TERMINAL_STATES) {
                byState.put(state, Counter.builder(SETTLED_METRIC)
                        .tag("signal", signal.name())
                        .tag("state", state)
                        .description("Signals by terminal state at decision time; "
                                + "NO_RESULT and NOT_EXECUTED are the fail-open states (ADR-15)")
                        .register(registry));
            }
            counters.put(signal, byState);
        }
    }

    /**
     * Counts one settled signal map. Cardinality is fixed at startup: {@link SignalConfig} values
     * times {@link #TERMINAL_STATES}.
     *
     * @param settledSignals the map as persisted with the decision
     */
    void recordSettled(Map<SignalConfig, SignalState> settledSignals) {
        settledSignals.forEach((signal, state) -> {
            var counter = counters.get(signal).get(stateTag(state));
            if (counter != null) {
                counter.increment();
            }
        });
    }

    /**
     * The {@code state} tag — the same vocabulary as the discriminator persisted on the signal map,
     * so a series and a stored row read alike.
     *
     * <p>{@code PENDING} is unreachable here: {@code DecisionEngine.evaluate} rejects an unsettled
     * map before the decision is written. It is mapped to a tag with no counter rather than thrown
     * on, because this runs after the decision is durable, where a metrics-only failure must not
     * surface.
     */
    private static String stateTag(SignalState state) {
        return switch (state) {
            case SignalState.Pending ignored -> "PENDING";
            case SignalState.Checked ignored -> "CHECKED";
            case SignalState.Scored ignored -> "SCORED";
            case SignalState.NoResult ignored -> "NO_RESULT";
            case SignalState.NotExecuted ignored -> "NOT_EXECUTED";
        };
    }
}
