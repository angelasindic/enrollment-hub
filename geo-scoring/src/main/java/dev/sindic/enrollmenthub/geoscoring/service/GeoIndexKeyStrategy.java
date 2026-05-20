package dev.sindic.enrollmenthub.geoscoring.service;

import org.springframework.stereotype.Component;

/**
 * Single source of truth for geo-index Redis key generation.
 *
 * <p>Keys follow the pattern {@code {prefix}:{countryCode}} where {@code countryCode}
 * is an ISO 3166-1 alpha-2 code (e.g. {@code geo:DE}, {@code geo:MC}).
 * Country-level partitioning keeps each GEO sorted set manageable and prevents
 * cross-border false positives in density detection.
 */
@Component
public class GeoIndexKeyStrategy {

    private final String prefix;

    public GeoIndexKeyStrategy(GeoIndexProperties properties) {
        this.prefix = properties.keyPrefix();
    }

    /**
     * Returns the Redis key for the geo-index partition of the given country.
     *
     * @param countryCode ISO 3166-1 alpha-2 country code (e.g. "DE", "MC")
     * @return Redis key in the format {@code {prefix}:{countryCode}}
     * @throws IllegalArgumentException if countryCode is null or blank
     */
    public String keyFor(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) {
            throw new IllegalArgumentException("countryCode must not be null or blank");
        }
        return prefix + ":" + countryCode;
    }

    /**
     * Returns the Redis key for the TTL tracking sorted set of the given country.
     *
     * @param countryCode ISO 3166-1 alpha-2 country code (e.g. "DE", "MC")
     * @return Redis key in the format {@code {prefix}:{countryCode}:ttl}
     */
    public String ttlKeyFor(String countryCode) {
        return keyFor(countryCode) + ":ttl";
    }

    String prefix() {
        return prefix;
    }
}
