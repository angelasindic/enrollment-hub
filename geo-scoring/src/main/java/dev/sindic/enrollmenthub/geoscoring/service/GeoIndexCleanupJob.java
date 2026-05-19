package dev.sindic.enrollmenthub.geoscoring.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Scheduled job that removes expired entries from the geo-index.
 *
 * <p>Discovers active country partitions via {@code SCAN} and delegates
 * to {@link GeoIndexService#cleanupExpired} for each partition.
 * The TTL window is defined in ADR-004 (default 48 hours).
 */
@Slf4j
@Component
public class GeoIndexCleanupJob {

    private final GeoIndexService geoIndexService;
    private final StringRedisTemplate redis;
    private final GeoIndexKeyStrategy keyStrategy;
    private final GeoIndexProperties properties;

    GeoIndexCleanupJob(GeoIndexService geoIndexService,
                       StringRedisTemplate redis,
                       GeoIndexKeyStrategy keyStrategy,
                       GeoIndexProperties properties) {
        this.geoIndexService = geoIndexService;
        this.redis = redis;
        this.keyStrategy = keyStrategy;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${geo-index.cleanup-interval:PT1H}")
    void cleanup() {
        var cutoff = Instant.now().minus(properties.ttl());
        var pattern = keyStrategy.prefix() + ":*";
        long totalRemoved = 0;

        try (var cursor = redis.scan(ScanOptions.scanOptions().match(pattern).count(100).build())) {
            while (cursor.hasNext()) {
                var key = cursor.next();
                if (key.endsWith(":ttl")) continue;

                var countryCode = key.substring(key.indexOf(':') + 1);
                totalRemoved += geoIndexService.cleanupExpired(countryCode, cutoff);
            }
        }

        log.info("Geo-index cleanup complete totalRemoved={}", totalRemoved);
    }
}
