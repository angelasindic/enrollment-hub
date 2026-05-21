package dev.sindic.enrollmenthub.decisionengine.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SignalStateTest {

    @Test
    void pending_isNotSettled() {
        var state = SignalState.pending();

        assertThat(state.hasSettled()).isFalse();
        assertThat(state.processingState()).isEqualTo(SignalProcessingState.PENDING);
        assertThat(state.outcome()).isNull();
        assertThat(state.riskLevel()).isNull();
        assertThat(state.reason()).isNull();
    }

    @Test
    void settledWithOutcome_isSettled() {
        var state = SignalState.settled(SignalOutcome.OK);

        assertThat(state.hasSettled()).isTrue();
        assertThat(state.processingState()).isEqualTo(SignalProcessingState.SETTLED);
        assertThat(state.outcome()).isEqualTo(SignalOutcome.OK);
        assertThat(state.riskLevel()).isNull();
    }

    @Test
    void settledWithRiskLevel_isSettled() {
        var state = SignalState.settled(RiskLevel.HIGH);

        assertThat(state.hasSettled()).isTrue();
        assertThat(state.processingState()).isEqualTo(SignalProcessingState.SETTLED);
        assertThat(state.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(state.outcome()).isNull();
    }

    @Test
    void settledWithoutResult_isSettledWithNullResultFields() {
        var state = SignalState.settledWithoutResult("geocoding_failed");

        assertThat(state.hasSettled()).isTrue();
        assertThat(state.processingState()).isEqualTo(SignalProcessingState.SETTLED);
        assertThat(state.outcome()).isNull();
        assertThat(state.riskLevel()).isNull();
        assertThat(state.reason()).isEqualTo("geocoding_failed");
    }

    @Test
    void failed_isSettled() {
        var state = SignalState.failed();

        assertThat(state.hasSettled()).isTrue();
        assertThat(state.processingState()).isEqualTo(SignalProcessingState.FAILED);
        assertThat(state.outcome()).isNull();
        assertThat(state.riskLevel()).isNull();
        assertThat(state.reason()).isNull();
    }

    @Test
    void allSignalOutcomes_producesCorrectState() {
        for (var outcome : SignalOutcome.values()) {
            var state = SignalState.settled(outcome);
            assertThat(state.outcome()).isEqualTo(outcome);
            assertThat(state.processingState()).isEqualTo(SignalProcessingState.SETTLED);
        }
    }

    @Test
    void allRiskLevels_producesCorrectState() {
        for (var level : RiskLevel.values()) {
            var state = SignalState.settled(level);
            assertThat(state.riskLevel()).isEqualTo(level);
            assertThat(state.processingState()).isEqualTo(SignalProcessingState.SETTLED);
        }
    }
}
