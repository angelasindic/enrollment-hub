package dev.sindic.enrollmenthub.geoscoring.service;

import dev.sindic.enrollmenthub.contracts.events.RiskLevel;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Configuration for the geo-index density detection.
 *
 * @param keyPrefix        Redis key prefix (key = {@code {keyPrefix}:{countryCode}})
 * @param searchLimit      COUNT cap for GEOSEARCH — truncation at this limit is a high-risk signal
 * @param radii            density check radii with their thresholds, ordered smallest to largest
 * @param ttl              active window for indexed entries (ADR-004); default 48 hours
 * @param cleanupBatchSize max members removed per Lua invocation to avoid blocking Redis
 * @param cleanupInterval  delay between scheduled cleanup runs
 */
@ConfigurationProperties("geo-index")
public record GeoIndexProperties(
        String keyPrefix,
        int searchLimit,
        List<RadiusThreshold> radii,
        Duration ttl,
        int cleanupBatchSize,
        Duration cleanupInterval
) {
    public GeoIndexProperties {
        if (keyPrefix == null || keyPrefix.isBlank()) {
            keyPrefix = "geo";
        }
        if (searchLimit <= 0) {
            searchLimit = 200;
        }
        if (radii == null || radii.isEmpty()) {
            radii = List.of(
                    new RadiusThreshold(100, 5, RiskLevel.HIGH),
                    new RadiusThreshold(250, 8, RiskLevel.MEDIUM),
                    new RadiusThreshold(500, 12, RiskLevel.LOW)
            );
        } else {
            radii = List.copyOf(radii);
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            ttl = Duration.ofHours(48);
        }
        if (cleanupBatchSize <= 0) {
            cleanupBatchSize = 1000;
        }
        if (cleanupInterval == null || cleanupInterval.isZero() || cleanupInterval.isNegative()) {
            cleanupInterval = Duration.ofHours(1);
        }
    }

    /**
     * @param radius    search radius in metres
     * @param threshold neighbor count at or above which this radius is flagged
     * @param riskLevel risk level assigned when this radius is triggered
     */
    public record RadiusThreshold(int radius, int threshold, RiskLevel riskLevel) {}
}
