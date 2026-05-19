package dev.sindic.enrollmenthub.geoscoring.service;

import dev.sindic.enrollmenthub.contracts.domain.Address;
import dev.sindic.enrollmenthub.contracts.events.GeoScoreResult;
import dev.sindic.enrollmenthub.contracts.events.RiskLevel;
import dev.sindic.enrollmenthub.geoscoring.amqp.GeoScoreResultPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class GeoScoringServiceTest {

    @Mock GeocodingService geocodingService;
    @Mock GeoIndexService geoIndexService;
    @Mock GeoScoreResultPublisher publisher;
    @InjectMocks GeoScoringService service;

    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final Address SHIPPING_ADDRESS = new Address(
            List.of("Avenue de Monte-Carlo"), "98000", "Monaco", null, "MC");
    private static final CoordinatesPayload COORDINATES = new CoordinatesPayload(43.7384, 7.4246);
    private static final DensityResult DENSITY_RESULT = new DensityResult(
            Map.of(100, 3, 250, 8), List.of(), false);

    @Test
    void scoreAddress_geocodingSucceeds_publishesDensityResult() {
        given(geocodingService.resolve(SHIPPING_ADDRESS)).willReturn(Optional.of(COORDINATES));
        given(geoIndexService.checkAndIndex("MC", 7.4246, 43.7384, REQUEST_ID.toString()))
                .willReturn(DENSITY_RESULT);
        var expectedEvent = new GeoScoreResult(
                REQUEST_ID, RiskLevel.LOW, null, Map.of(100, 3, 250, 8), List.of(), 43.7384, 7.4246);
        given(geoIndexService.toEvent(REQUEST_ID, DENSITY_RESULT, COORDINATES))
                .willReturn(expectedEvent);

        service.scoreAddress(REQUEST_ID, SHIPPING_ADDRESS);

        then(publisher).should().publish(expectedEvent);
    }

    @Test
    void scoreAddress_geocodingFails_publishesNoResult() {
        given(geocodingService.resolve(SHIPPING_ADDRESS)).willReturn(Optional.empty());
        var noResultEvent = new GeoScoreResult(
                REQUEST_ID, null, "geocoding_failed", Map.of(), List.of(), null, null);
        given(geoIndexService.toNotAvailableEvent(REQUEST_ID)).willReturn(noResultEvent);

        service.scoreAddress(REQUEST_ID, SHIPPING_ADDRESS);

        then(geoIndexService).should(never()).checkAndIndex(anyString(), anyDouble(), anyDouble(), anyString());
        then(publisher).should().publish(noResultEvent);
    }

    @Test
    void scoreAddress_geocodingThrows_propagates() {
        given(geocodingService.resolve(any())).willThrow(new RuntimeException("provider down"));

        assertThatThrownBy(() -> service.scoreAddress(REQUEST_ID, SHIPPING_ADDRESS))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("provider down");

        then(publisher).should(never()).publish(any());
    }

    @Test
    void scoreAddress_publisherFailurePropagates() {
        given(geocodingService.resolve(SHIPPING_ADDRESS)).willReturn(Optional.of(COORDINATES));
        given(geoIndexService.checkAndIndex(eq("MC"), anyDouble(), anyDouble(), anyString()))
                .willReturn(DENSITY_RESULT);
        given(geoIndexService.toEvent(any(), any(), any()))
                .willReturn(new GeoScoreResult(
                        REQUEST_ID, RiskLevel.LOW, null, Map.of(), List.of(), 43.7384, 7.4246));
        doThrow(new RuntimeException("broker down")).when(publisher).publish(any());

        assertThatThrownBy(() -> service.scoreAddress(REQUEST_ID, SHIPPING_ADDRESS))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("broker down");
    }
}
