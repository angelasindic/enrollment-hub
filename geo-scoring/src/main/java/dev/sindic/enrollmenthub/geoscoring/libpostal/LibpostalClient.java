package dev.sindic.enrollmenthub.geoscoring.libpostal;

import dev.sindic.enrollmenthub.geoscoring.service.TransientGeocodingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;

@Slf4j
@Component
public class LibpostalClient {

    private static final ParameterizedTypeReference<List<AddressComponent>> COMPONENTS_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient libpostalRestClient;

    public LibpostalClient(RestClient libpostalRestClient) {
        this.libpostalRestClient = libpostalRestClient;
    }

    /**
     * Parses {@code address} into labeled components.
     *
     * <p>Error handling mirrors {@code NominatimGeocodingProvider}: transient
     * provider conditions (5xx, transport error, timeout, 408/429) throw
     * {@link TransientGeocodingException} so the AMQP listener retry chain
     * can replay; other 4xx (genuinely unparseable input) collapse to an
     * empty list and let {@code AddressNormalizationService} apply its
     * ADR-012 fail-open fallback.
     */
    public List<AddressComponent> parse(String address) {
        try {
            var components = libpostalRestClient.get()
                    .uri("/parse?address={address}", address)
                    .retrieve()
                    .body(COMPONENTS_TYPE);
            return components != null ? components : List.of();
        } catch (HttpClientErrorException ex) {
            var status = ex.getStatusCode();
            if (status == HttpStatus.TOO_MANY_REQUESTS || status == HttpStatus.REQUEST_TIMEOUT) {
                throw new TransientGeocodingException(
                        "libpostal transient client error " + status, ex);
            }
            log.warn("libpostal client error status={} body={}", status, ex.getResponseBodyAsString());
            return List.of();
        } catch (HttpServerErrorException ex) {
            throw new TransientGeocodingException(
                    "libpostal server error " + ex.getStatusCode(), ex);
        } catch (ResourceAccessException ex) {
            throw new TransientGeocodingException("libpostal unreachable", ex);
        }
    }
}
