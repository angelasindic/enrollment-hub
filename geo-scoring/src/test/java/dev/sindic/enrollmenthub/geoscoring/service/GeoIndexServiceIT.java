package dev.sindic.enrollmenthub.geoscoring.service;

import dev.sindic.enrollmenthub.contracts.events.RiskLevel;
import dev.sindic.enrollmenthub.geoscoring.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class GeoIndexServiceIT extends BaseIntegrationTest {

    @Autowired
    private GeoIndexService geoIndexService;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private GeoIndexKeyStrategy keyStrategy;

    @BeforeEach
    void cleanIndex() {
        var keys = redis.keys("geo:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void checkAndIndex_emptyIndex_returnsZeroCounts() {
        var result = geoIndexService.checkAndIndex("MC", 7.4246, 43.7384, "acc-1");

        assertThat(result.neighborCounts()).containsEntry(100, 0).containsEntry(250, 0).containsEntry(500, 0);
        assertThat(result.triggeredRiskLevels()).isEmpty();
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void checkAndIndex_indexesPoint() {
        geoIndexService.checkAndIndex("MC", 7.4246, 43.7384, "acc-1");

        var key = keyStrategy.keyFor("MC");
        assertThat(redis.hasKey(key)).isTrue();
    }

    @Test
    void checkAndIndex_detectsNeighbors() {
        // Seed 4 points near Monaco Casino (~7.4246, 43.7384), all within 100m
        for (int i = 1; i <= 4; i++) {
            geoIndexService.checkAndIndex("MC", 7.4246 + i * 0.00001, 43.7384, "seed-" + i);
        }

        // 5th point should see 4 neighbors at 100m — below threshold (5)
        var result = geoIndexService.checkAndIndex("MC", 7.4246, 43.7384, "acc-5");
        assertThat(result.neighborCounts().get(100)).isEqualTo(4);
        assertThat(result.triggeredRiskLevels()).isEmpty();

        // 6th point should see 5 neighbors at 100m — meets threshold
        var result2 = geoIndexService.checkAndIndex("MC", 7.4246, 43.7384, "acc-6");
        assertThat(result2.neighborCounts().get(100)).isEqualTo(5);
        assertThat(result2.triggeredRiskLevels()).contains(RiskLevel.HIGH);
    }

    @Test
    void checkAndIndex_countryIsolation() {
        // Index 5 points in MC
        for (int i = 1; i <= 5; i++) {
            geoIndexService.checkAndIndex("MC", 7.4246, 43.7384, "mc-" + i);
        }

        // Query in FR at same coordinates — should see 0 neighbors
        var result = geoIndexService.checkAndIndex("FR", 7.4246, 43.7384, "fr-1");

        assertThat(result.neighborCounts().get(100)).isZero();
        assertThat(result.neighborCounts().get(250)).isZero();
        assertThat(result.neighborCounts().get(500)).isZero();
    }

    @Test
    void cleanupExpired_removesExpiredEntries_doesNotAffectDensityCounts() {
        var geoKey = keyStrategy.keyFor("MC");
        var ttlKey = keyStrategy.ttlKeyFor("MC");
        var pastEpoch = Instant.now().minusSeconds(3600).getEpochSecond();

        // Seed 5 entries with a far-past TTL timestamp directly in Redis
        for (int i = 1; i <= 5; i++) {
            redis.opsForGeo().add(geoKey,
                    new org.springframework.data.geo.Point(7.4246 + i * 0.00001, 43.7384),
                    "old-" + i);
            redis.opsForZSet().add(ttlKey, "old-" + i, pastEpoch);
        }

        // Index 1 recent entry via normal path (gets current timestamp)
        geoIndexService.checkAndIndex("MC", 7.4246, 43.7384, "recent-1");

        // Before cleanup: should see 5 old + 0 recent neighbors (recent was added last)
        var beforeCleanup = geoIndexService.checkAndIndex("MC", 7.4246, 43.7384, "probe-before");
        assertThat(beforeCleanup.neighborCounts().get(100)).isGreaterThanOrEqualTo(6);

        // Cleanup with cutoff between old and recent
        var cutoff = Instant.now().minusSeconds(60);
        long removed = geoIndexService.cleanupExpired("MC", cutoff);
        assertThat(removed).isEqualTo(5);

        // After cleanup: density should only reflect recent + probe entries
        var afterCleanup = geoIndexService.checkAndIndex("MC", 7.4246, 43.7384, "probe-after");
        assertThat(afterCleanup.neighborCounts().get(100)).isLessThanOrEqualTo(3);

        // TTL tracking set should no longer contain expired members
        var ttlSize = redis.opsForZSet().size(ttlKey);
        assertThat(ttlSize).isEqualTo(3L); // recent-1, probe-before, probe-after
    }

    @Test
    void checkAndIndex_concurrentBurst_atMostOneScoresLow() throws Exception {
        // Simulate a fraud burst: 6 concurrent requests at the same location.
        // With atomic Lua: only the first should see 0 neighbors; subsequent ones
        // should see increasing counts. At most 1 can score below the 100m threshold (5).
        int burstSize = 6;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<DensityResult>>();
            for (int i = 0; i < burstSize; i++) {
                var addressId = "burst-" + i;
                futures.add(executor.submit(() ->
                        geoIndexService.checkAndIndex("MC", 7.4246, 43.7384, addressId)));
            }

            var results = new ArrayList<DensityResult>();
            for (var future : futures) {
                results.add(future.get());
            }

            // Collect counts at 100m radius — they should be strictly increasing: 0,1,2,3,4,5
            var countsAt100m = results.stream()
                    .map(r -> r.neighborCounts().get(100))
                    .sorted()
                    .toList();

            assertThat(countsAt100m).isEqualTo(List.of(0, 1, 2, 3, 4, 5));

            // At most 1 result should have 0 neighbors at 100m (the first one processed)
            long belowThreshold = results.stream()
                    .filter(r -> r.neighborCounts().get(100) < 5)
                    .count();
            assertThat(belowThreshold).isLessThanOrEqualTo(5);

            // At least 1 should trigger the 100m threshold
            long triggered = results.stream()
                    .filter(r -> r.triggeredRiskLevels().contains(RiskLevel.HIGH))
                    .count();
            assertThat(triggered).isGreaterThanOrEqualTo(1);
        }
    }
}
