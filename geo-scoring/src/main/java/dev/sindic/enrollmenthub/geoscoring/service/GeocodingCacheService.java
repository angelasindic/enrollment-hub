// Target:  JDK 25 / Spring Boot 4.x
// Status:  Reference
// Assumes: Redis/Valkey reachable; StringRedisTemplate and JsonMapper auto-configured

package dev.sindic.enrollmenthub.geoscoring.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Optional;

/**
 * Geocoding cache backed by Redis/Valkey.
 *
 * <p>Key:   SHA-256 hex digest of the normalized address (see {@link GeocodingCacheKeyService}).
 * <p>Value: JSON-serialized {@link CoordinatesPayload}.
 * <p>TTL:   caller-supplied (no external ToS constraints — ADR-013).
 */
@Slf4j
@Service
public class GeocodingCacheService {

    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;

    public GeocodingCacheService(StringRedisTemplate redis, JsonMapper jsonMapper) {
        this.redis = redis;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Returns cached coordinates for the given cache key, or empty on a miss or deserialization failure.
     */
    public Optional<CoordinatesPayload> lookup(String cacheKey) {
        return Optional.ofNullable(redis.opsForValue().get(cacheKey))
                .flatMap(raw -> deserialize(cacheKey, raw));
    }

    private Optional<CoordinatesPayload> deserialize(String cacheKey, String raw) {
        try {
            var coordinates = jsonMapper.readValue(raw, CoordinatesPayload.class);
            log.info("Geocoding cache hit key={}", cacheKey);
            return Optional.of(coordinates);
        } catch (JacksonException e) {
            log.warn("Failed to deserialize cached coordinates key={}, evicting", cacheKey, e);
            redis.delete(cacheKey);
            return Optional.empty();
        }
    }

    /**
     * Stores coordinates in the cache under the given key with the specified TTL.
     */
    public void store(String cacheKey, CoordinatesPayload coordinates, Duration ttl) {
        try {
            var raw = jsonMapper.writeValueAsString(coordinates);
            redis.opsForValue().set(cacheKey, raw, ttl);
        } catch (JacksonException e) {
            log.warn("Failed to serialize coordinates for caching key={}", cacheKey, e);
        }
    }
}
