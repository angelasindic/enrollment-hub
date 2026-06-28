package dev.sindic.enrollmenthub.geoscoring.service;

import dev.sindic.enrollmenthub.geoscoring.BaseIntegrationTest;
import dev.sindic.enrollmenthub.geoscoring.libpostal.AddressComponent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class NominatimGeocodingProviderIT extends BaseIntegrationTest {

    @Autowired
    GeocodingProvider geocodingProvider;

    @Test
    void geocode_knownMonacoAddress_returnsCoordinates() {
        var result = geocodingProvider.geocode(List.of(
                new AddressComponent("road", "avenue de monte-carlo"),
                new AddressComponent("city", "monaco"),
                new AddressComponent("country", "mc")
        ));

        assertThat(result).isPresent();
        assertThat(result.get().latitude()).isCloseTo(43.74, within(0.05));
        assertThat(result.get().longitude()).isCloseTo(7.42, within(0.05));
    }

    @Test
    void geocode_anotherMonacoAddress_returnsCoordinates() {
        var result = geocodingProvider.geocode(List.of(
                new AddressComponent("road", "place du casino"),
                new AddressComponent("city", "monaco"),
                new AddressComponent("country", "mc")
        ));

        assertThat(result).isPresent();
        assertThat(result.get().latitude()).isCloseTo(43.74, within(0.05));
        assertThat(result.get().longitude()).isCloseTo(7.43, within(0.05));
    }

    @Test
    void geocode_unknownAddress_returnsEmpty() {
        var result = geocodingProvider.geocode(List.of(
                new AddressComponent("road", "this address does not exist anywhere"),
                new AddressComponent("postcode", "99999")
        ));

        assertThat(result).isEmpty();
    }

    @Test
    void geocode_providerIsNominatimInstance() {
        assertThat(geocodingProvider).isInstanceOf(NominatimGeocodingProvider.class);
    }
}
