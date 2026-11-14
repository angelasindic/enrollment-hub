package dev.sindic.enrollmenthub.geoscoring.service;

import dev.sindic.enrollmenthub.geoscoring.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GeoIndexCleanupJobIT extends BaseIntegrationTest {

    @Autowired
    private GeoIndexCleanupJob cleanupJob;

    @Autowired
    private GeoIndexService geoIndexService;

    @Autowired
    private GeoIndexProperties properties;

    @Autowired
    private GeoIndexKeyStrategy keyStrategy;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void cleanIndex() {
        var keys = redis.keys("geo:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void cleanup_removesAnExpiredMemberFromIndexTtlSetAndCountsHash_inEveryPartition() {
        geoIndexService.checkAndIndex("DE", 13.4050, 52.5200, "e-expired");
        geoIndexService.checkAndIndex("DE", 13.4051, 52.5200, "e-current");
        geoIndexService.checkAndIndex("MC", 7.4246, 43.7384, "e-expired");
        expire("DE", "e-expired");
        expire("MC", "e-expired");

        cleanupJob.cleanup();

        assertThat(membersOf("DE")).containsExactly("e-current");
        assertThat(redis.opsForZSet().range(keyStrategy.ttlKeyFor("DE"), 0, -1)).containsExactly("e-current");
        assertThat(redis.<String, String>opsForHash().keys(keyStrategy.countsKeyFor("DE")))
                .containsExactly("e-current");

        // The last member of a partition takes all three keys with it.
        assertThat(redis.hasKey(keyStrategy.keyFor("MC"))).isFalse();
        assertThat(redis.hasKey(keyStrategy.ttlKeyFor("MC"))).isFalse();
        assertThat(redis.hasKey(keyStrategy.countsKeyFor("MC"))).isFalse();
    }

    @Test
    void cleanup_afterExpiry_theSameEnrollmentIsScoredAsNew() {
        geoIndexService.checkAndIndex("DE", 13.4050, 52.5200, "e-1");
        geoIndexService.checkAndIndex("DE", 13.4050, 52.5200, "e-2");
        expire("DE", "e-1");

        cleanupJob.cleanup();

        // With the stored counts gone, e-1 is counted again and finds e-2.
        var result = geoIndexService.checkAndIndex("DE", 13.4050, 52.5200, "e-1");
        assertThat(result.neighborCounts()).containsEntry(100, 1);
    }

    /** Moves the member's insertion time to one hour before the TTL cutoff. */
    private void expire(String countryCode, String member) {
        var insertedAt = Instant.now().minus(properties.ttl()).minusSeconds(3600).getEpochSecond();
        redis.opsForZSet().add(keyStrategy.ttlKeyFor(countryCode), member, insertedAt);
    }

    private Set<String> membersOf(String countryCode) {
        return redis.opsForZSet().range(keyStrategy.keyFor(countryCode), 0, -1);
    }
}
