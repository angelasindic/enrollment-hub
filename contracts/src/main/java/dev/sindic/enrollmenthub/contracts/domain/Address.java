package dev.sindic.enrollmenthub.contracts.domain;

import java.util.List;
import java.util.Objects;

public record Address(
        List<String> streetLines,
        String postalCode,
        String city,
        String subregion, //province or region
        /* ISO 3166-1 alpha-2 country code; used as geo-index partition key. */
        String countryCode
) {
    public Address {
        Objects.requireNonNull(countryCode, "countryCode must not be null");
        streetLines = streetLines == null ? List.of() : List.copyOf(streetLines);
    }
}

