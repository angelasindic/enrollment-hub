package dev.sindic.enrollmenthub.geoscoring.service;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Single source of truth for geo-index Redis key generation.
 *
 * <p>The country code is wrapped in a Redis hash tag, so keys look like
 * {@code geo:{DE}} (the index), {@code geo:{DE}:ttl} (its TTL companion) and
 * {@code geo:{DE}:counts} (the counts hash). The shared tag forces all three keys onto the
 * same cluster slot, which keeps the multi-key density and cleanup Lua scripts
 * cross-slot-safe under Redis Cluster (ADR-11); on a single instance the tag is inert.
 * Country-level partitioning keeps each GEO sorted set manageable and prevents cross-border
 * false positives in density detection.
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
     * @return Redis key with the country code wrapped in a hash tag, e.g. {@code geo:{DE}}
     * @throws IllegalArgumentException if countryCode is null or blank
     */
    public String keyFor(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) {
            throw new IllegalArgumentException("countryCode must not be null or blank");
        }
        // {countryCode} is a Redis hash tag: it colocates this key, ttlKeyFor and countsKeyFor
        // on one cluster slot so the multi-key Lua scripts never hit CROSSSLOT (ADR-11).
        return prefix + ":{" + countryCode + "}";
    }

    /**
     * Returns the Redis key for the TTL tracking sorted set of the given country.
     *
     * @param countryCode ISO 3166-1 alpha-2 country code (e.g. "DE", "MC")
     * @return TTL companion key, e.g. {@code geo:{DE}:ttl}; shares {@link #keyFor}'s hash
     *         tag so both colocate on the same cluster slot
     */
    public String ttlKeyFor(String countryCode) {
        return keyFor(countryCode) + ":ttl";
    }

    /**
     * Returns the Redis key for the counts hash of the given country. The hash maps each
     * enrollment ID to the neighbor counts of its first density check.
     *
     * @param countryCode ISO 3166-1 alpha-2 country code (e.g. "DE", "MC")
     * @return counts hash key, e.g. {@code geo:{DE}:counts}; shares {@link #keyFor}'s hash
     *         tag so it colocates with the index and its TTL companion on one cluster slot
     */
    public String countsKeyFor(String countryCode) {
        return keyFor(countryCode) + ":counts";
    }

    /**
     * Returns the country code of a geo-index key; the inverse of {@link #keyFor}.
     *
     * @param key a Redis key, typically from a {@code SCAN} over the prefix
     * @return the country code if {@code key} is an index key such as {@code geo:{DE}}; empty
     *         for the companion keys ({@code geo:{DE}:ttl}, {@code geo:{DE}:counts}) and for
     *         a key of any other shape
     */
    Optional<String> countryCodeOf(String key) {
        var open = prefix + ":{";
        if (!key.startsWith(open) || !key.endsWith("}")) {
            return Optional.empty();
        }
        var countryCode = key.substring(open.length(), key.length() - 1);
        return countryCode.isBlank() ? Optional.empty() : Optional.of(countryCode);
    }

    String prefix() {
        return prefix;
    }
}
