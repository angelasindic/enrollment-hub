package dev.sindic.enrollmenthub.geoscoring.service;

import dev.sindic.enrollmenthub.geoscoring.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class GeocodingCacheServiceIT extends BaseIntegrationTest {

    @Autowired
    GeocodingCacheService cacheService;

    @Autowired
    GeocodingCacheKeyService keyService;

    @Test
    void lookup_cacheMiss_returnsEmpty() {
        var result = cacheService.lookup("nonexistent-key");

        assertThat(result).isEmpty();
    }

    @Test
    void lookup_afterStore_returnsCachedCoordinates() {
        var key = keyService.keyFor("berlin 123 main street");
        var coordinates = new CoordinatesPayload(52.5200, 13.4050);

        cacheService.store(key, coordinates, Duration.ofMinutes(1));
        var result = cacheService.lookup(key);

        assertThat(result).isPresent();
        assertThat(result.get().latitude()).isEqualTo(52.5200);
        assertThat(result.get().longitude()).isEqualTo(13.4050);
    }

    @Test
    void lookup_differentAddresses_independentCacheEntries() {
        var key1 = keyService.keyFor("berlin 123 main street");
        var key2 = keyService.keyFor("hamburg 45 reeperbahn");

        cacheService.store(key1, new CoordinatesPayload(52.52, 13.40), Duration.ofMinutes(1));

        assertThat(cacheService.lookup(key1)).isPresent();
        assertThat(cacheService.lookup(key2)).isEmpty();
    }
}
