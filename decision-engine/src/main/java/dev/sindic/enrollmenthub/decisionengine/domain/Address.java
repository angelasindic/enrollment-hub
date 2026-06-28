package dev.sindic.enrollmenthub.decisionengine.domain;

import java.util.List;

public record Address(
        List<String> streetLines,
        String postalCode,
        String city,
        String subregion,
        String countryCode
) {
    public Address {
        streetLines = streetLines == null ? List.of() : List.copyOf(streetLines);
    }
}
