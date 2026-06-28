package dev.sindic.enrollmenthub.geoscoring.service;

import dev.sindic.enrollmenthub.contracts.domain.Address;
import dev.sindic.enrollmenthub.geoscoring.libpostal.AddressComponent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeocodingServiceTest {

    private static final Address ADDRESS =
            new Address(List.of("Keizersgracht 1"), "1015CJ", "Amsterdam", null, "NL");
    private static final List<AddressComponent> COMPONENTS = List.of(
            new AddressComponent("city", "amsterdam"),
            new AddressComponent("house_number", "1"),
            new AddressComponent("road", "keizersgracht"),
            new AddressComponent("postcode", "1015cj")
    );
    private static final ParsedAddress PARSED = new ParsedAddress(COMPONENTS);
    private static final String CACHE_KEY = "abc123";
    private static final CoordinatesPayload COORDINATES = new CoordinatesPayload(52.3676, 4.9041);
    private static final Duration TTL = Duration.ofDays(90);

    @Mock AddressNormalizationService normalizationService;
    @Mock GeocodingCacheKeyService cacheKeyService;
    @Mock GeocodingCacheService cacheService;
    @Mock GeocodingProvider provider;

    GeocodingService createService() {
        return new GeocodingService(normalizationService, cacheKeyService, cacheService, provider, TTL);
    }

    private void stubNormalizationAndKey() {
        when(normalizationService.normalize(ADDRESS)).thenReturn(PARSED);
        when(cacheKeyService.keyFor(PARSED.canonical())).thenReturn(CACHE_KEY);
    }

    @Test
    void resolve_cacheHit_returnsCachedAndSkipsProvider() {
        stubNormalizationAndKey();
        when(cacheService.lookup(CACHE_KEY)).thenReturn(Optional.of(COORDINATES));

        var result = createService().resolve(ADDRESS);

        assertThat(result).contains(COORDINATES);
        verify(provider, never()).geocode(any());
        verify(cacheService, never()).store(anyString(), any(), any());
    }

    @Test
    void resolve_cacheMiss_callsProviderAndStoresResult() {
        stubNormalizationAndKey();
        when(cacheService.lookup(CACHE_KEY)).thenReturn(Optional.empty());
        when(provider.geocode(COMPONENTS)).thenReturn(Optional.of(COORDINATES));

        var result = createService().resolve(ADDRESS);

        assertThat(result).contains(COORDINATES);
        verify(cacheService).store(CACHE_KEY, COORDINATES, TTL);
    }

    @Test
    void resolve_cacheMiss_providerReturnsEmpty_returnsEmptyAndDoesNotStore() {
        stubNormalizationAndKey();
        when(cacheService.lookup(CACHE_KEY)).thenReturn(Optional.empty());
        when(provider.geocode(COMPONENTS)).thenReturn(Optional.empty());

        var result = createService().resolve(ADDRESS);

        assertThat(result).isEmpty();
        verify(cacheService, never()).store(anyString(), any(), any());
    }

    @Test
    void resolve_cacheMiss_storesWithConfiguredTtl() {
        stubNormalizationAndKey();
        when(cacheService.lookup(CACHE_KEY)).thenReturn(Optional.empty());
        when(provider.geocode(COMPONENTS)).thenReturn(Optional.of(COORDINATES));

        createService().resolve(ADDRESS);

        verify(cacheService).store(eq(CACHE_KEY), eq(COORDINATES), eq(TTL));
    }
}
