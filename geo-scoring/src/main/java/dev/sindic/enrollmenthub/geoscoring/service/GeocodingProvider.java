package dev.sindic.enrollmenthub.geoscoring.service;

import dev.sindic.enrollmenthub.geoscoring.libpostal.AddressComponent;

import java.util.List;
import java.util.Optional;

/**
 * Resolves parsed address components to geographic coordinates.
 */
public interface GeocodingProvider {

    Optional<CoordinatesPayload> geocode(List<AddressComponent> components);
}
