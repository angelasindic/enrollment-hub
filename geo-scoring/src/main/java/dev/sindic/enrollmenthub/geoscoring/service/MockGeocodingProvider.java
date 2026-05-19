// Target:  JDK 25 / Spring Boot 4.x
// Status:  Reference

package dev.sindic.enrollmenthub.geoscoring.service;

import dev.sindic.enrollmenthub.geoscoring.libpostal.AddressComponent;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "geocoding.provider", havingValue = "mock", matchIfMissing = true)
public class MockGeocodingProvider implements GeocodingProvider {

    private static final Map<String, CoordinatesPayload> KNOWN_ADDRESSES = Map.of(
            "amsterdam",  new CoordinatesPayload(52.3676, 4.9041),
            "berlin",     new CoordinatesPayload(52.5200, 13.4050),
            "london",     new CoordinatesPayload(51.5074, -0.1278),
            "paris",      new CoordinatesPayload(48.8566, 2.3522),
            "brussels",   new CoordinatesPayload(50.8503, 4.3517)
    );

    public MockGeocodingProvider() {
        log.info("MockGeocodingProvider active — external geocoding API disabled");
    }

    @Override
    public Optional<CoordinatesPayload> geocode(List<AddressComponent> components) {
        var joined = components.stream()
                .map(c -> c.value().toLowerCase())
                .collect(java.util.stream.Collectors.joining(" "));

        return KNOWN_ADDRESSES.entrySet().stream()
                .filter(entry -> joined.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();
    }
}
