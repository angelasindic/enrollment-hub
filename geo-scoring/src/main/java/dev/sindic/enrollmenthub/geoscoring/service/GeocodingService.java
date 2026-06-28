package dev.sindic.enrollmenthub.geoscoring.service;

import dev.sindic.enrollmenthub.contracts.domain.Address;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Orchestrates the geocoding pipeline: normalise the address, check the cache,
 * and on a miss resolve via the active {@link GeocodingProvider} and store the
 * result for future reuse.
 */
@Slf4j
@Service
public class GeocodingService {

    private final AddressNormalizationService normalizationService;
    private final GeocodingCacheKeyService cacheKeyService;
    private final GeocodingCacheService cacheService;
    private final GeocodingProvider provider;
    private final Duration cacheTtl;

    public GeocodingService(AddressNormalizationService normalizationService,
                            GeocodingCacheKeyService cacheKeyService,
                            GeocodingCacheService cacheService,
                            GeocodingProvider provider,
                            @Value("${geocoding.cache.ttl:P90D}") Duration cacheTtl) {
        this.normalizationService = normalizationService;
        this.cacheKeyService = cacheKeyService;
        this.cacheService = cacheService;
        this.provider = provider;
        this.cacheTtl = cacheTtl;
    }

    /**
     * Resolves an address to coordinates, using the geocoding cache when possible.
     *
     * <ol>
     *   <li>Normalise the address via libpostal</li>
     *   <li>Compute the HMAC-SHA256 cache key</li>
     *   <li>Look up the cache — return immediately on a hit</li>
     *   <li>On a miss, call the geocoding provider</li>
     *   <li>Store the result in the cache for future reuse</li>
     * </ol>
     *
     * @return coordinates, or empty if the provider cannot resolve the address
     */
    public Optional<CoordinatesPayload> resolve(Address address) {
        var parsed = normalizationService.normalize(address);
        var cacheKey = cacheKeyService.keyFor(parsed.canonical());

        var cached = cacheService.lookup(cacheKey);
        if (cached.isPresent()) {
            return cached;
        }

        log.info("Geocoding cache miss — resolving via provider address={}", parsed.canonical());
        var resolved = provider.geocode(parsed.components());

        resolved.ifPresent(coordinates -> cacheService.store(cacheKey, coordinates, cacheTtl));

        return resolved;
    }
}
