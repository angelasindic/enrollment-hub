package dev.sindic.enrollmenthub.contracts.events;

/**
 * Settled result for one signal inside {@link EnrollmentDecisionEvent}. Consumers only ever see a
 * decision once every signal has settled, so no in-progress state is published.
 *
 * <p>At most one result field is set: check-style signals populate {@code outcome}, score-style
 * signals populate {@code riskLevel}. Both are null when the signal produced no result, and
 * {@code reason} then says why.
 *
 * @param outcome   check-style result, or null
 * @param riskLevel score-style result, or null
 * @param reason    why no result was produced
 */
public record EnrollmentSignal(
        SignalOutcome outcome,
        RiskLevel riskLevel,
        String reason
) {}
