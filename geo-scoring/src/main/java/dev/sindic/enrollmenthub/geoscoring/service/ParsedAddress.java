package dev.sindic.enrollmenthub.geoscoring.service;

import dev.sindic.enrollmenthub.geoscoring.libpostal.AddressComponent;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public record ParsedAddress(String canonical, List<AddressComponent> components) {

    public ParsedAddress(List<AddressComponent> components) {
        this(
            components.stream()
                    .sorted(Comparator.comparing(AddressComponent::label))
                    .map(c -> c.label() + ":" + c.value())
                    .collect(Collectors.joining(" ")),
            List.copyOf(components)
        );
    }
}
