package dev.sindic.enrollmenthub.geoscoring.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class GeoIndexKeyStrategyTest {

    private final GeoIndexKeyStrategy strategy = new GeoIndexKeyStrategy(
            new GeoIndexProperties("geo", 200, List.of(), null, 0, null));

    @Test
    void keyFor_returnsCountryPartitionedKey() {
        assertThat(strategy.keyFor("DE")).isEqualTo("geo:DE");
    }

    @Test
    void keyFor_differentCountries_differentKeys() {
        assertThat(strategy.keyFor("DE")).isNotEqualTo(strategy.keyFor("FR"));
    }

    @Test
    void keyFor_usesConfiguredPrefix() {
        var custom = new GeoIndexKeyStrategy(
                new GeoIndexProperties("geoindex", 200, List.of(), null, 0, null));

        assertThat(custom.keyFor("MC")).isEqualTo("geoindex:MC");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\t"})
    void keyFor_rejectsNullOrBlankCountryCode(String countryCode) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> strategy.keyFor(countryCode));
    }
}
