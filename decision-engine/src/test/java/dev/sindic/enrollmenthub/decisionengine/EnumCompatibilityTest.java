package dev.sindic.enrollmenthub.decisionengine;

import dev.sindic.enrollmenthub.contracts.domain.PaymentType;
import dev.sindic.enrollmenthub.decisionengine.api.EnrollmentRequest;
import dev.sindic.enrollmenthub.contracts.events.DecisionResult;
import dev.sindic.enrollmenthub.contracts.events.RiskLevel;
import dev.sindic.enrollmenthub.contracts.events.CheckOutcome;
import dev.sindic.enrollmenthub.contracts.events.SignalOutcome;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards against silent drift between the decision-engine domain enums and the enums of the two
 * channels either side of them: the {@code contracts} module (messaging) and the {@code api}
 * request records (REST). Each channel declares its own types (ADR-06 §One channel, one
 * contract), so nothing but these assertions keeps the three in step.
 */
class EnumCompatibilityTest {

    @Test
    void domainRiskLevelEqualsContracts() {
        Set<String> domain    = enumNames(dev.sindic.enrollmenthub.decisionengine.domain.RiskLevel.class);
        Set<String> contracts = enumNames(RiskLevel.class);
        assertThat(domain).isEqualTo(contracts);
    }

    @Test
    void domainSignalOutcomeIsSubsetOfContracts() {
        // Subset, not equality: the domain enum is {OK, FAILED} because "ran but produced no
        // result" is the SignalState.NoResult variant, which applies to score-style signals too —
        // something SignalOutcome.NO_RESULT never could. The contract keeps NO_RESULT because
        // FraudCheckResult still carries it on the wire (ADR-06).
        Set<String> domain    = enumNames(dev.sindic.enrollmenthub.decisionengine.domain.CheckOutcome.class);
        Set<String> contracts = enumNames(SignalOutcome.class);
        assertThat(contracts).containsAll(domain);
    }

    @Test
    void domainDecisionResultEqualsContracts() {
        Set<String> domain    = enumNames(dev.sindic.enrollmenthub.decisionengine.domain.DecisionResult.class);
        Set<String> contracts = enumNames(DecisionResult.class);
        assertThat(domain).isEqualTo(contracts);
    }

    @Test
    void domainPaymentTypeEqualsContracts() {
        Set<String> domain    = enumNames(dev.sindic.enrollmenthub.decisionengine.domain.PaymentType.class);
        Set<String> contracts = enumNames(PaymentType.class);
        assertThat(domain).isEqualTo(contracts);
    }

    @Test
    void apiPaymentTypeEqualsDomain() {
        // Closes the chain: api == domain, and domainPaymentTypeEqualsContracts covers the rest.
        // A value accepted over REST with no domain counterpart fails here, not at valueOf() time
        // inside EnrollmentController.createDomainRequest.
        Set<String> api    = enumNames(EnrollmentRequest.PaymentTypeDto.class);
        Set<String> domain = enumNames(dev.sindic.enrollmenthub.decisionengine.domain.PaymentType.class);
        assertThat(api).isEqualTo(domain);
    }

    private static <E extends Enum<E>> Set<String> enumNames(Class<E> type) {
        return Arrays.stream(type.getEnumConstants())
                .map(Enum::name)
                .collect(Collectors.toSet());
    }
}
