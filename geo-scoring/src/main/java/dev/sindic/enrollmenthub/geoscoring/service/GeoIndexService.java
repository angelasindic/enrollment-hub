package dev.sindic.enrollmenthub.geoscoring.service;

import dev.sindic.enrollmenthub.contracts.events.GeoScoreResult;
import dev.sindic.enrollmenthub.contracts.events.RiskLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Performs atomic density check + index operations against Redis GEO sorted sets.
 *
 * <p>Uses a Lua script ({@code geo-density.lua}) to atomically execute GEOSEARCH at
 * multiple radii followed by GEOADD in a single Redis call. This eliminates the
 * concurrent GEOSEARCH/GEOADD race (F-3) where simultaneous requests could all
 * score LOW before any GEOADD completes.
 */
@Slf4j
@EnableConfigurationProperties(GeoIndexProperties.class)
@Service
public class GeoIndexService {

    private final StringRedisTemplate redis;
    private final GeoIndexKeyStrategy keyStrategy;
    private final GeoIndexProperties properties;
    private final DefaultRedisScript<List<Long>> densityScript;
    private final DefaultRedisScript<Long> cleanupScript;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public GeoIndexService(StringRedisTemplate redis,
                           GeoIndexKeyStrategy keyStrategy,
                           GeoIndexProperties properties) {
        this.redis = redis;
        this.keyStrategy = keyStrategy;
        this.properties = properties;

        this.densityScript = new DefaultRedisScript<>();
        this.densityScript.setLocation(new ClassPathResource("scripts/geo-density.lua"));
        // setResultType only accepts the erased Class — element type is enforced by the script contract.
        this.densityScript.setResultType((Class) List.class);

        this.cleanupScript = new DefaultRedisScript<>();
        this.cleanupScript.setLocation(new ClassPathResource("scripts/geo-cleanup.lua"));
        this.cleanupScript.setResultType(Long.class);
    }

    /**
     * Atomically checks density at all configured radii and indexes the point.
     *
     * <p>The Lua script ensures no concurrent worker can interleave between the
     * GEOSEARCH reads and the GEOADD write — the score-before-index ordering is
     * preserved within the atomic script execution.
     *
     * @param countryCode ISO 3166-1 alpha-2 country code (partition key)
     * @param longitude   WGS 84 longitude
     * @param latitude    WGS 84 latitude
     * @param enrollmentId   enrollment request identifier to index
     * @return density result with neighbor counts, triggered thresholds, and truncation flag
     */
    public DensityResult checkAndIndex(String countryCode, double longitude, double latitude,
                                       String enrollmentId) {
        var geoKey = keyStrategy.keyFor(countryCode);
        var ttlKey = keyStrategy.ttlKeyFor(countryCode);
        var radii = properties.radii();
        var searchLimit = properties.searchLimit();

        var args = new ArrayList<String>();
        args.add(String.valueOf(longitude));
        args.add(String.valueOf(latitude));
        args.add(enrollmentId);
        args.add(String.valueOf(searchLimit));
        args.add(String.valueOf(Instant.now().getEpochSecond()));
        for (var rt : radii) {
            args.add(String.valueOf(rt.radius()));
        }

        List<Long> counts;
        try {
            counts = redis.execute(densityScript, List.of(geoKey, ttlKey), args.toArray());
        } catch (DataAccessException ex) {
            log.error("Geo-density script failed key={} cause={}",
                    geoKey, ex.getMostSpecificCause().getMessage());
            throw ex;   // re-throw unchanged — retry/DLQ handles it
        }

        var result = buildResult(counts, radii, searchLimit);

        log.info("Density check key={} neighborCounts={} triggered={} truncated={}",
                geoKey, result.neighborCounts(), result.triggeredRiskLevels(), result.truncated());

        return result;
    }

    private DensityResult buildResult(List<Long> counts,
                                      List<GeoIndexProperties.RadiusThreshold> radii,
                                      int searchLimit) {
        var neighborCounts = new LinkedHashMap<Integer, Integer>();
        var triggeredRiskLevels = new ArrayList<RiskLevel>();
        boolean truncated = false;

        for (int i = 0; i < radii.size(); i++) {
            var rt = radii.get(i);
            int count = counts.get(i).intValue();
            neighborCounts.put(rt.radius(), count);

            if (count >= rt.threshold()) {
                triggeredRiskLevels.add(rt.riskLevel());
            }

            if (count >= searchLimit) {
                truncated = true;
            }
        }

        return new DensityResult(neighborCounts, triggeredRiskLevels, truncated);
    }

    /**
     * Assembles a {@link GeoScoreResult} event from the density result.
     *
     * @param enrollmentId   correlation ID from the original {@code EnrollmentAccepted} event
     * @param result      density check result
     * @param coordinates geocoded coordinates, or {@code null} if geocoding failed
     * @return event ready for publishing to the decision-engine
     */
    public GeoScoreResult toEvent(UUID enrollmentId, DensityResult result,
                                    CoordinatesPayload coordinates) {
        var triggeredThresholds = properties.radii().stream()
                .filter(rt -> result.neighborCounts().getOrDefault(rt.radius(), 0) >= rt.threshold())
                .map(GeoIndexProperties.RadiusThreshold::radius)
                .toList();

        return new GeoScoreResult(
                enrollmentId,
                result.resolveRiskLevel(),
                null,
                result.neighborCounts(),
                triggeredThresholds,
                coordinates != null ? coordinates.latitude() : null,
                coordinates != null ? coordinates.longitude() : null
        );
    }

    /**
     * Assembles a {@link GeoScoreResult} event for the case where geocoding failed
     * and no density check could be performed. The null {@code riskLevel} signals a
     * no-result condition; the decision-engine maps this to a SETTLED + no-result signal
     * state (fail-open). No entry is added to the geo-index.
     */
    public GeoScoreResult toNotAvailableEvent(UUID enrollmentId) {
        return new GeoScoreResult(
                enrollmentId,
                null,
                "geocoding_failed",
                Map.of(),
                List.of(),
                null,
                null
        );
    }

    /**
     * Removes expired members from the geo-index and TTL tracking set for a country.
     * Runs the cleanup Lua script in batches until all expired members are removed.
     *
     * @param countryCode ISO 3166-1 alpha-2 country code
     * @param cutoff      entries inserted at or before this instant are removed
     * @return total number of members removed
     */
    public long cleanupExpired(String countryCode, Instant cutoff) {
        var geoKey = keyStrategy.keyFor(countryCode);
        var ttlKey = keyStrategy.ttlKeyFor(countryCode);
        var keys = List.of(geoKey, ttlKey);
        var batchSize = String.valueOf(properties.cleanupBatchSize());
        var cutoffEpoch = String.valueOf(cutoff.getEpochSecond());

        long totalRemoved = 0;
        long removed;
        do {
            removed = redis.execute(cleanupScript, keys, cutoffEpoch, batchSize);
            totalRemoved += removed;
        } while (removed == properties.cleanupBatchSize());

        if (totalRemoved > 0) {
            log.info("Cleanup key={} removed={}", geoKey, totalRemoved);
        }

        return totalRemoved;
    }
}
