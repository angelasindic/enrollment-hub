package dev.sindic.enrollmenthub.decisionengine;

import dev.sindic.enrollmenthub.contracts.events.DecisionResult;
import dev.sindic.enrollmenthub.contracts.events.RiskLevel;
import dev.sindic.enrollmenthub.contracts.events.SignalOutcome;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards against silent drift between decision-engine domain enums and their
 * contracts module counterparts.
 */
class EnumCompatibilityTest {

    @Test
    void domainRiskLevelEqualsContracts() {
        Set<String> domain    = enumNames(dev.sindic.enrollmenthub.decisionengine.domain.RiskLevel.class);
        Set<String> contracts = enumNames(RiskLevel.class);
        assertThat(domain).isEqualTo(contracts);
    }

    @Test
    void domainSignalOutcomeEqualsContracts() {
        Set<String> domain    = enumNames(dev.sindic.enrollmenthub.decisionengine.domain.SignalOutcome.class);
        Set<String> contracts = enumNames(SignalOutcome.class);
        assertThat(domain).isEqualTo(contracts);
    }

    @Test
    void domainDecisionResultEqualsContracts() {
        Set<String> domain    = enumNames(dev.sindic.enrollmenthub.decisionengine.domain.DecisionResult.class);
        Set<String> contracts = enumNames(DecisionResult.class);
        assertThat(domain).isEqualTo(contracts);
    }

    private static <E extends Enum<E>> Set<String> enumNames(Class<E> type) {
        return Arrays.stream(type.getEnumConstants())
                .map(Enum::name)
                .collect(Collectors.toSet());
    }
}
