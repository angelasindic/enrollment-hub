package dev.sindic.enrollmenthub.geoscoring.service;

import dev.sindic.enrollmenthub.geoscoring.libpostal.AddressComponent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockGeocodingProviderTest {

    private final MockGeocodingProvider provider = new MockGeocodingProvider();

    @Test
    void geocode_knownCity_returnsCoordinates() {
        var result = provider.geocode(List.of(new AddressComponent("city", "amsterdam")));

        assertThat(result).isPresent();
        assertThat(result.get().latitude()).isEqualTo(52.3676);
        assertThat(result.get().longitude()).isEqualTo(4.9041);
    }

    @Test
    void geocode_anotherKnownCity_returnsCoordinates() {
        var result = provider.geocode(List.of(new AddressComponent("city", "berlin")));

        assertThat(result).isPresent();
        assertThat(result.get().latitude()).isEqualTo(52.5200);
        assertThat(result.get().longitude()).isEqualTo(13.4050);
    }

    @Test
    void geocode_caseInsensitive_returnsCoordinates() {
        var result = provider.geocode(List.of(new AddressComponent("city", "AMSTERDAM")));

        assertThat(result).isPresent();
        assertThat(result.get().latitude()).isEqualTo(52.3676);
    }

    @Test
    void geocode_cityEmbeddedInFullAddress_returnsCoordinates() {
        var result = provider.geocode(List.of(
                new AddressComponent("road", "keizersgracht"),
                new AddressComponent("house_number", "1"),
                new AddressComponent("postcode", "1015cj"),
                new AddressComponent("city", "amsterdam"),
                new AddressComponent("country", "netherlands")
        ));

        assertThat(result).isPresent();
        assertThat(result.get().latitude()).isEqualTo(52.3676);
    }

    @Test
    void geocode_unknownAddress_returnsEmpty() {
        var result = provider.geocode(List.of(new AddressComponent("city", "tokyo")));

        assertThat(result).isEmpty();
    }

    @Test
    void geocode_emptyComponents_returnsEmpty() {
        var result = provider.geocode(List.of());

        assertThat(result).isEmpty();
    }

    @Test
    void geocode_isDeterministic() {
        var components = List.of(new AddressComponent("city", "berlin"));

        assertThat(provider.geocode(components)).isEqualTo(provider.geocode(components));
    }
}
