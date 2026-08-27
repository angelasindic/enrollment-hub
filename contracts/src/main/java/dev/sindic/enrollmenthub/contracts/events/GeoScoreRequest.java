package dev.sindic.enrollmenthub.contracts.events;

import dev.sindic.enrollmenthub.contracts.domain.Address;

import java.util.Objects;
import java.util.UUID;

/**
 * Command requesting a geo-density check. Deliberately least-privilege: the shipping address to
 * geocode and the id to correlate the reply, and nothing else of the enrollment.
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
