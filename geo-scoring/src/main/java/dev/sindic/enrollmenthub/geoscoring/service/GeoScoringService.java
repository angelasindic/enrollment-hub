package dev.sindic.enrollmenthub.geoscoring.service;

import dev.sindic.enrollmenthub.contracts.domain.Address;
import dev.sindic.enrollmenthub.contracts.events.GeoScoreResult;
import dev.sindic.enrollmenthub.geoscoring.amqp.GeoScoreResultPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Orchestrates the geo-scoring pipeline for an incoming enrollment request:
 * geocode the shipping address, check and index geo-density, and publish the
 * {@link GeoScoreResult} result back to the decision-engine.
 *
 * <p>If geocoding fails (unresolvable address, provider outage), publishes a
 * no-result signal so the decision-engine can proceed with its fail-open decision rules.
 */
@Service
@Slf4j
public class GeoScoringService {

    private final GeocodingService geocodingService;
    private final GeoIndexService geoIndexService;
    private final GeoScoreResultPublisher publisher;

    GeoScoringService(GeocodingService geocodingService,
                      GeoIndexService geoIndexService,
                      GeoScoreResultPublisher publisher) {
        this.geocodingService = geocodingService;
        this.geoIndexService = geoIndexService;
        this.publisher = publisher;
    }

    public void scoreAddress(UUID requestId, Address shippingAddress) {
        var coordinates = geocodingService.resolve(shippingAddress).orElse(null);

        GeoScoreResult event;
        if (coordinates != null) {
            var densityResult = geoIndexService.checkAndIndex(
                    shippingAddress.countryCode(),
                    coordinates.longitude(), coordinates.latitude(),
                    requestId.toString());
            event = geoIndexService.toEvent(requestId, densityResult, coordinates);
        } else {
            log.warn("Geocoding failed — emitting no-result signal state");
            event = geoIndexService.toNotAvailableEvent(requestId);
        }

        publisher.publish(event);
    }
}
