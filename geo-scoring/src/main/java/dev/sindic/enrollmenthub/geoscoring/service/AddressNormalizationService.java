package dev.sindic.enrollmenthub.geoscoring.service;

import dev.sindic.enrollmenthub.contracts.domain.Address;
import dev.sindic.enrollmenthub.geoscoring.libpostal.AddressComponent;
import dev.sindic.enrollmenthub.geoscoring.libpostal.LibpostalClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class AddressNormalizationService {

    private final LibpostalClient libpostalClient;

    public AddressNormalizationService(LibpostalClient libpostalClient) {
        this.libpostalClient = libpostalClient;
    }

    /**
     * Returns a canonical normalized form of the given address.
     *
     * <p>Flattens the {@link Address} fields into a single string, then parses it via
     * libpostal into labeled components, sorts by label, and joins the values.
     *
     * <p><b>Failure model.</b> {@link LibpostalClient} distinguishes transient outages
     * ({@link TransientGeocodingException}) from input rejection (empty list on 4xx).
     * Both collapse here to the same fail-open behaviour — fall back to the trimmed
     * raw string under a synthetic {@code address} label — so geo-scoring can still
     * proceed. ADR-012 trades cache hit rate for availability: a libpostal outage
     * does <i>not</i> drain the listener retry chain; Nominatim's free-form fallback
     * is the recovery path. Any other exception propagates so genuine bugs surface.
     */
    public ParsedAddress normalize(Address address) {
        var raw = toRawString(address);
        List<AddressComponent> components;
        try {
            components = libpostalClient.parse(raw);
        } catch (TransientGeocodingException e) {
            log.warn("libpostal unavailable, falling back to pre-processed address: {}", e.getMessage());
            return new ParsedAddress(List.of(new AddressComponent("address", raw)));
        }
        if (components.isEmpty()) {
            log.warn("libpostal returned no components for address, using pre-processed input");
            return new ParsedAddress(List.of(new AddressComponent("address", raw)));
        }
        return new ParsedAddress(components);
    }

    /**
     * Flattens an {@link Address} into a single string suitable for libpostal,
     * trimming each field and joining with ", ".
     */
    private String toRawString(Address address) {
        return Stream.concat(
                        address.streetLines().stream(),
                        Stream.of(address.postalCode(), address.city(), address.subregion(), address.countryCode())
                )
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining(", "));
    }
}
