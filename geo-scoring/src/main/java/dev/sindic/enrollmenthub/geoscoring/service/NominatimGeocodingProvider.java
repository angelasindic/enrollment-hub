// Target:  JDK 25 / Spring Boot 4.x
// Status:  Reference

package dev.sindic.enrollmenthub.geoscoring.service;

import dev.sindic.enrollmenthub.geoscoring.libpostal.AddressComponent;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Component
@ConditionalOnProperty(name = "geocoding.provider", havingValue = "nominatim")
public class NominatimGeocodingProvider implements GeocodingProvider {

    private static final String METRIC_UNMAPPED_LABEL = "geocoding.unmapped_label";
    private static final String METRIC_FALLBACK = "geocoding.fallback";

    private final RestClient nominatimRestClient;
    private final MeterRegistry meterRegistry;

    public NominatimGeocodingProvider(RestClient nominatimRestClient, MeterRegistry meterRegistry) {
        log.info("NominatimGeocodingProvider active — using self-hosted Nominatim");
        this.nominatimRestClient = nominatimRestClient;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Optional<CoordinatesPayload> geocode(List<AddressComponent> components) {
        recordUnmappedLabels(components);

        var structured = callNominatim(buildStructuredQuery(components), "structured");
        if (structured.isPresent()) {
            return structured;
        }

        var fallback = callNominatim(buildFreeFormQuery(components), "fallback");
        meterRegistry.counter(METRIC_FALLBACK, "outcome", fallback.isPresent() ? "found" : "empty")
                .increment();
        return fallback;
    }

    private Optional<CoordinatesPayload> callNominatim(String query, String stage) {
        try {
            List<NominatimResult> results = nominatimRestClient.get()
                    .uri(query)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (results == null || results.isEmpty()) {
                log.info("Nominatim {} query returned no results query={}", stage, query);
                return Optional.empty();
            }

            var first = results.getFirst();
            var coordinates = new CoordinatesPayload(
                    Double.parseDouble(first.lat()),
                    Double.parseDouble(first.lon())
            );
            log.debug("Nominatim {} query geocoded to lat={}, lon={}",
                    stage, coordinates.latitude(), coordinates.longitude());
            return Optional.of(coordinates);

        } catch (Exception ex) {
            log.warn("Nominatim {} query failed", stage, ex);
            return Optional.empty();
        }
    }

    private void recordUnmappedLabels(List<AddressComponent> components) {
        for (var c : components) {
            if (NominatimLabel.fromLibpostal(c.label()).isEmpty()) {
                log.warn("Nominatim ignored unmapped libpostal label={} value={}", c.label(), c.value());
                meterRegistry.counter(METRIC_UNMAPPED_LABEL, "label", c.label()).increment();
            }
        }
    }

    private String buildStructuredQuery(List<AddressComponent> components) {
        var params = new EnumMap<NominatimLabel, String>(NominatimLabel.class);
        for (var c : components) {
            NominatimLabel.fromLibpostal(c.label()).ifPresent(label -> params.putIfAbsent(label, c.value()));
        }

        var sb = new StringBuilder("/search?format=jsonv2&limit=1");

        String road = params.get(NominatimLabel.ROAD);
        if (road != null) {
            String houseNumber = params.get(NominatimLabel.HOUSE_NUMBER);
            String street = houseNumber != null ? houseNumber + " " + road : road;
            appendParam(sb, "street", street);
        }
        appendParam(sb, "city",         params.get(NominatimLabel.CITY));
        appendParam(sb, "postalcode",   params.get(NominatimLabel.POSTCODE));
        appendParam(sb, "countrycodes", params.get(NominatimLabel.COUNTRY));
        appendParam(sb, "state",        params.get(NominatimLabel.STATE));

        return sb.toString();
    }

    private String buildFreeFormQuery(List<AddressComponent> components) {
        String q = components.stream()
                .map(AddressComponent::value)
                .collect(Collectors.joining(" "));
        return "/search?format=jsonv2&limit=1&q=" + URLEncoder.encode(q, StandardCharsets.UTF_8);
    }

    private void appendParam(StringBuilder sb, String key, String value) {
        if (value != null && !value.isBlank()) {
            sb.append('&').append(key).append('=')
              .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
    }

    record NominatimResult(String lat, String lon) {}
}
