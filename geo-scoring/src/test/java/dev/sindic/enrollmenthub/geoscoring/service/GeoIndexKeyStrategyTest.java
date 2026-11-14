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
        assertThat(strategy.keyFor("DE")).isEqualTo("geo:{DE}");
    }

    @Test
    void allKeys_shareHashTag_soTheyColocateOnOneClusterSlot() {
        // The density/cleanup Lua scripts touch all three keys; under Redis Cluster they must
        // hash to the same slot. The shared {countryCode} hash tag guarantees it (ADR-11).
        assertThat(strategy.keyFor("DE")).isEqualTo("geo:{DE}");
        assertThat(strategy.ttlKeyFor("DE")).isEqualTo("geo:{DE}:ttl");
        assertThat(strategy.countsKeyFor("DE")).isEqualTo("geo:{DE}:counts");
    }

    @Test
    void keyFor_differentCountries_differentKeys() {
        assertThat(strategy.keyFor("DE")).isNotEqualTo(strategy.keyFor("FR"));
    }

    @Test
    void keyFor_usesConfiguredPrefix() {
        var custom = new GeoIndexKeyStrategy(
                new GeoIndexProperties("geoindex", 200, List.of(), null, 0, null));

        assertThat(custom.keyFor("MC")).isEqualTo("geoindex:{MC}");
    }

    @Test
    void countryCodeOf_indexKey_returnsTheCountryCodeWithoutTheHashTagBraces() {
        assertThat(strategy.countryCodeOf(strategy.keyFor("DE"))).contains("DE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"geo:{DE}:ttl", "geo:{DE}:counts", "geo:{}", "geo:DE", "other:{DE}"})
    void countryCodeOf_anyOtherKey_returnsEmpty(String key) {
        assertThat(strategy.countryCodeOf(key)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\t"})
    void keyFor_rejectsNullOrBlankCountryCode(String countryCode) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> strategy.keyFor(countryCode));
    }
}
