package dev.sindic.enrollmenthub.contracts.events;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Result of the geo-density check. Owned by geo-scoring.
 *
 * <p>A null {@code riskLevel} means no density measurement was possible and {@code noResultReason}
 * says why — a distinct condition from any {@link SignalOutcome}, which this event does not carry.
 *
 * <p>{@link RiskLevel#EXTREME} means the measurement saturated: a search hit its result cap, so the
 * true neighbour count is unknown and at least as high as reported.
 */
public record GeoScoreResult(
        UUID enrollmentId,
        /* Null when geocoding failed and no density measurement was possible. */
        RiskLevel riskLevel,
        /* Non-null when riskLevel is null; describes why scoring could not run. */
        String noResultReason,
        /* Radius (metres) → neighbor count. */
        Map<Integer, Integer> neighborCounts,
        /* Radii where the configured density threshold was met or exceeded. */
        List<Integer> triggeredThresholds
) {
    public GeoScoreResult {
        Objects.requireNonNull(enrollmentId, "enrollmentId must not be null");
        neighborCounts = neighborCounts == null ? Map.of() : Map.copyOf(neighborCounts);
        triggeredThresholds = triggeredThresholds == null ? List.of() : List.copyOf(triggeredThresholds);
    }
}
