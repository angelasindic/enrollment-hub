package dev.sindic.enrollmenthub.geoscoring.service;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

enum NominatimLabel {
    HOUSE_NUMBER("house_number"),
    ROAD("road"),
    CITY("city"),
    POSTCODE("postcode"),
    COUNTRY("country"),
    STATE("state");

    private final String libpostal;

    NominatimLabel(String libpostal) {
        this.libpostal = libpostal;
    }

    private static final Map<String, NominatimLabel> BY_LIBPOSTAL =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(l -> l.libpostal, l -> l));

    static Optional<NominatimLabel> fromLibpostal(String label) {
        return Optional.ofNullable(BY_LIBPOSTAL.get(label));
    }
}
