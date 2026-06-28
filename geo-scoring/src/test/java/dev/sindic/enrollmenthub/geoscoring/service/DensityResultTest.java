package dev.sindic.enrollmenthub.geoscoring.service;

import dev.sindic.enrollmenthub.contracts.events.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DensityResultTest {

    @Test
    void resolveRiskLevel_noTriggers_returnsLow() {
        var result = new DensityResult(Map.of(100, 2, 250, 4, 500, 8), List.of(), false);

        assertThat(result.resolveRiskLevel()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void resolveRiskLevel_lowOnly_returnsLow() {
        var result = new DensityResult(Map.of(100, 1, 250, 3, 500, 15), List.of(RiskLevel.LOW), false);

        assertThat(result.resolveRiskLevel()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void resolveRiskLevel_mediumTriggered_returnsMedium() {
        var result = new DensityResult(Map.of(100, 3, 250, 10, 500, 15),
                List.of(RiskLevel.MEDIUM, RiskLevel.LOW), false);

        assertThat(result.resolveRiskLevel()).isEqualTo(RiskLevel.MEDIUM);
    }

    @Test
    void resolveRiskLevel_highTriggered_returnsHigh() {
        var result = new DensityResult(Map.of(100, 6, 250, 10, 500, 15),
                List.of(RiskLevel.HIGH, RiskLevel.MEDIUM, RiskLevel.LOW), false);

        assertThat(result.resolveRiskLevel()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void resolveRiskLevel_truncated_returnsExtreme() {
        var result = new DensityResult(Map.of(100, 1, 250, 3, 500, 200), List.of(RiskLevel.LOW), true);

        assertThat(result.resolveRiskLevel()).isEqualTo(RiskLevel.EXTREME);
    }

    @Test
    void resolveRiskLevel_truncatedWithNoTriggers_returnsExtreme() {
        var result = new DensityResult(Map.of(100, 0, 250, 0, 500, 0), List.of(), true);

        assertThat(result.resolveRiskLevel()).isEqualTo(RiskLevel.EXTREME);
    }

    @Test
    void resolveRiskLevel_highestWins() {
        var result = new DensityResult(Map.of(100, 7, 250, 9, 500, 5),
                List.of(RiskLevel.HIGH, RiskLevel.MEDIUM), false);

        assertThat(result.resolveRiskLevel()).isEqualTo(RiskLevel.HIGH);
    }
}
