package dev.sindic.enrollmenthub.contracts.events;

import dev.sindic.enrollmenthub.contracts.domain.Address;

import java.util.Objects;
import java.util.UUID;

/**
 * Command dispatched by the decision-engine to geo-scoring on the {@code enrollment.check.request}
 * exchange (routing key {@code geo.score}). Least-privilege payload — geo-scoring needs only the
 * shipping address to geocode and the {@code enrollmentId} to correlate; no other enrollment data
 * is shared.
 */
public record GeoScoreRequest(
        UUID enrollmentId,
        Address shippingAddress
) {
    public GeoScoreRequest {
        Objects.requireNonNull(enrollmentId, "enrollmentId must not be null");
        Objects.requireNonNull(shippingAddress, "shippingAddress must not be null");
    }
}
