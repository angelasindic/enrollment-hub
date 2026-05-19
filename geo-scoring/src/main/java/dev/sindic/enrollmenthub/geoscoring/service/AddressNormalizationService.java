// Target:  JDK 25 / Spring Boot 4.x
// Status:  Reference
// Assumes: LibpostalClient configured; libpostal service reachable at startup

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
     * <p>Fails open: if libpostal is unavailable or returns no components, the
     * flattened raw string is returned so that geo-scoring can proceed.
     */
    public ParsedAddress normalize(Address address) {
        var raw = toRawString(address);
        try {
            var components = libpostalClient.parse(raw);
            if (components.isEmpty()) {
                log.warn("libpostal returned no components for address, using pre-processed input");
                return new ParsedAddress(List.of(new AddressComponent("address", raw)));
            }
            return new ParsedAddress(components);
        } catch (Exception e) {
            log.warn("libpostal unavailable, falling back to pre-processed address: {}", e.getMessage());
            return new ParsedAddress(List.of(new AddressComponent("address", raw)));
        }
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
