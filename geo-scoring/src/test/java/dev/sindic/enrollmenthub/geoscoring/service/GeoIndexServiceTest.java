package dev.sindic.enrollmenthub.geoscoring.service;

import dev.sindic.enrollmenthub.contracts.events.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GeoIndexServiceTest {

    private StringRedisTemplate redis;
    private GeoIndexService service;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        var properties = new GeoIndexProperties("geo", 200, List.of(
                new GeoIndexProperties.RadiusThreshold(100, 5, RiskLevel.HIGH),
                new GeoIndexProperties.RadiusThreshold(250, 8, RiskLevel.MEDIUM),
                new GeoIndexProperties.RadiusThreshold(500, 12, RiskLevel.LOW)
        ), Duration.ofHours(48), 1000, Duration.ofHours(1));
        var keyStrategy = new GeoIndexKeyStrategy(properties);
        service = new GeoIndexService(redis, keyStrategy, properties);
    }

    @Test
    void checkAndIndex_noNeighbors_returnsEmptyThresholds() {
        stubScript(List.of(0L, 0L, 0L));

        var result = service.checkAndIndex("DE", 13.405, 52.52, "acc-1");

        assertThat(result.neighborCounts())
                .containsEntry(100, 0)
                .containsEntry(250, 0)
                .containsEntry(500, 0);
        assertThat(result.triggeredRiskLevels()).isEmpty();
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void checkAndIndex_aboveThreshold_triggersRadius() {
        stubScript(List.of(6L, 3L, 1L));

        var result = service.checkAndIndex("DE", 13.405, 52.52, "acc-1");

        assertThat(result.neighborCounts()).containsEntry(100, 6);
        assertThat(result.triggeredRiskLevels()).containsExactly(RiskLevel.HIGH);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void checkAndIndex_multipleThresholdsTriggered() {
        stubScript(List.of(5L, 10L, 15L));

        var result = service.checkAndIndex("FR", 2.35, 48.86, "acc-2");

        assertThat(result.triggeredRiskLevels()).containsExactly(RiskLevel.HIGH, RiskLevel.MEDIUM, RiskLevel.LOW);
    }

    @Test
    void checkAndIndex_truncated_setsFlag() {
        stubScript(List.of(200L, 200L, 200L));

        var result = service.checkAndIndex("DE", 13.405, 52.52, "acc-1");

        assertThat(result.truncated()).isTrue();
        assertThat(result.triggeredRiskLevels()).containsExactly(RiskLevel.HIGH, RiskLevel.MEDIUM, RiskLevel.LOW);
    }

    @SuppressWarnings("unchecked")
    @Test
    void checkAndIndex_usesCorrectKeys() {
        stubScript(List.of(0L, 0L, 0L));

        service.checkAndIndex("MC", 7.42, 43.73, "acc-3");

        var keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(RedisScript.class), keysCaptor.capture(), any(Object[].class));

        assertThat(keysCaptor.getValue()).containsExactly("geo:MC", "geo:MC:ttl");
    }

    @SuppressWarnings("unchecked")
    @Test
    void checkAndIndex_passesCorrectArgs() {
        stubScript(List.of(0L, 0L, 0L));

        service.checkAndIndex("DE", 13.405, 52.52, "acc-1");

        var argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(any(RedisScript.class), anyList(), argsCaptor.capture());

        var args = argsCaptor.getValue();
        assertThat(args[0]).isEqualTo("13.405");
        assertThat(args[1]).isEqualTo("52.52");
        assertThat(args[2]).isEqualTo("acc-1");
        assertThat(args[3]).isEqualTo("200");
        // args[4] is epoch seconds — verify it's a plausible timestamp
        assertThat(Long.parseLong((String) args[4])).isGreaterThan(0);
        assertThat(args[5]).isEqualTo("100");
        assertThat(args[6]).isEqualTo("250");
        assertThat(args[7]).isEqualTo("500");
    }

    // ── toEvent assembly ──────────────────────────────────────────────────────

    @Test
    void toEvent_mapsAllFields() {
        var enrollmentId = UUID.randomUUID();
        var density = new DensityResult(
                Map.of(100, 6, 250, 10, 500, 15),
                List.of(RiskLevel.HIGH, RiskLevel.MEDIUM, RiskLevel.LOW), false);
        var coordinates = new CoordinatesPayload(52.52, 13.405);

        var event = service.toEvent(enrollmentId, density, coordinates);

        assertThat(event.enrollmentId()).isEqualTo(enrollmentId);
        assertThat(event.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(event.noResultReason()).isNull();
        assertThat(event.neighborCounts()).isEqualTo(Map.of(100, 6, 250, 10, 500, 15));
        assertThat(event.triggeredThresholds()).containsExactly(100, 250, 500);
        assertThat(event.latitude()).isEqualTo(52.52);
        assertThat(event.longitude()).isEqualTo(13.405);
    }

    @Test
    void toEvent_nullCoordinates_setsNullLatLon() {
        var event = service.toEvent(
                UUID.randomUUID(),
                new DensityResult(Map.of(100, 0, 250, 0, 500, 0), List.of(), false),
                null);

        assertThat(event.latitude()).isNull();
        assertThat(event.longitude()).isNull();
        assertThat(event.riskLevel()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void toEvent_triggeredThresholds_derivedFromConfig() {
        // Only 100m triggered (count 6 >= threshold 5), 250m not (count 3 < 8)
        var density = new DensityResult(
                Map.of(100, 6, 250, 3, 500, 1),
                List.of(RiskLevel.HIGH), false);

        var event = service.toEvent(UUID.randomUUID(), density, new CoordinatesPayload(43.73, 7.42));

        assertThat(event.triggeredThresholds()).containsExactly(100);
    }

    @SuppressWarnings("unchecked")
    private void stubScript(List<Long> counts) {
        doReturn(counts).when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }
}
