package dev.sindic.enrollmenthub.geoscoring.service;

import dev.sindic.enrollmenthub.contracts.events.RiskLevel;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Result of an atomic density check + index operation.
 *
 * @param neighborCounts      radius (metres) → neighbor count found within that radius
 * @param triggeredRiskLevels risk levels of radii where the configured density threshold was met
 * @param truncated           {@code true} if any GEOSEARCH hit the COUNT limit
 */
public record DensityResult(
        Map<Integer, Integer> neighborCounts,
        List<RiskLevel> triggeredRiskLevels,
        boolean truncated
) {
    public DensityResult {
        neighborCounts = neighborCounts == null ? Map.of() : Map.copyOf(neighborCounts);
        triggeredRiskLevels = triggeredRiskLevels == null ? List.of() : List.copyOf(triggeredRiskLevels);
    }

    /**
     * Resolves the overall risk level.
     *
     * <p>Rules (applied in order):
     * <ol>
     *   <li>Truncation (any GEOSEARCH hit COUNT limit) → {@link RiskLevel#EXTREME}</li>
     *   <li>Highest risk level among triggered radii wins</li>
     *   <li>No radii triggered → {@link RiskLevel#LOW}</li>
     * </ol>
     */
    public RiskLevel resolveRiskLevel() {
        if (truncated) {
            return RiskLevel.EXTREME;
        }
        if (triggeredRiskLevels.isEmpty()) {
            return RiskLevel.LOW;
        }
        return triggeredRiskLevels.stream()
                .max(Comparator.naturalOrder())
                .orElse(RiskLevel.LOW);
    }
}
