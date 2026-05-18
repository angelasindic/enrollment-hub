package dev.sindic.enrollmenthub.contracts.events;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Published by geo-scoring, consumed by the decision-engine.
 *
 * <p>{@code riskLevel} is {@code null} when geocoding failed and no density
 * measurement could be produced. In that case {@code noResultReason} is
 * non-null. The decision-engine maps a null {@code riskLevel} to a
 * {@code SETTLED + NO_RESULT} signal state (fail-open).
 *
 * <p>{@link RiskLevel#EXTREME} indicates density saturation — at least one
 * GEOSEARCH radius hit the COUNT 200 result cap.
 */
public record GeoScoreResult(
        UUID requestId,
        /* Null when geocoding failed and no density measurement was possible. */
        RiskLevel riskLevel,
        /* Non-null when riskLevel is null; describes why scoring could not run. */
        String noResultReason,
        /* Radius (metres) → neighbor count. */
        Map<Integer, Integer> neighborCounts,
        /* Radii where the configured density threshold was met or exceeded. */
        List<Integer> triggeredThresholds,
        /* Geocoded latitude, or null if geocoding failed. */
        Double latitude,
        /* Geocoded longitude, or null if geocoding failed. */
        Double longitude
) {
    public GeoScoreResult {
        Objects.requireNonNull(requestId, "requestId must not be null");
        neighborCounts = neighborCounts == null ? Map.of() : Map.copyOf(neighborCounts);
        triggeredThresholds = triggeredThresholds == null ? List.of() : List.copyOf(triggeredThresholds);
    }
}
