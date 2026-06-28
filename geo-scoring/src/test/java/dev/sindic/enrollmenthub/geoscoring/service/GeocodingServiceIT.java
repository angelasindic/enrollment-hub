package dev.sindic.enrollmenthub.geoscoring.service;

import dev.sindic.enrollmenthub.contracts.domain.Address;
import dev.sindic.enrollmenthub.geoscoring.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GeocodingServiceIT extends BaseIntegrationTest {

    @Autowired GeocodingService geocodingService;
    @Autowired GeocodingCacheKeyService cacheKeyService;
    @Autowired GeocodingCacheService cacheService;
    @Autowired AddressNormalizationService normalizationService;

    private static final Address MONACO_ADDRESS =
            new Address(List.of("Avenue de Monte-Carlo"), "98000", "Monaco", null, "MC");

    @Test
    void resolve_cacheMiss_resolvesViaProviderAndReturnsCoordinates() {
        var result = geocodingService.resolve(MONACO_ADDRESS);

        assertThat(result).isPresent();
        assertThat(result.get().latitude()).isCloseTo(43.74, within(0.05));
        assertThat(result.get().longitude()).isCloseTo(7.42, within(0.05));
    }

    @Test
    void resolve_secondCall_usesCache() {
        // First call — cache miss, populates cache
        var first = geocodingService.resolve(MONACO_ADDRESS);
        assertThat(first).isPresent();

        // Verify the cache was populated
        var normalized = normalizationService.normalize(MONACO_ADDRESS);
        var cacheKey = cacheKeyService.keyFor(normalized.canonical());
        var cached = cacheService.lookup(cacheKey);

        assertThat(cached).isPresent();
        assertThat(cached.get().latitude()).isEqualTo(first.get().latitude());
        assertThat(cached.get().longitude()).isEqualTo(first.get().longitude());

        // Second call — should use cache (same result)
        var second = geocodingService.resolve(MONACO_ADDRESS);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void resolve_unknownAddress_returnsEmpty() {
        var unknown = new Address(List.of("Nonexistent Street 99999"), "00000", "Nowhere", null, "XX");

        var result = geocodingService.resolve(unknown);

        assertThat(result).isEmpty();
    }
}
