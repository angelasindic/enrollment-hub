package dev.sindic.enrollmenthub.geoscoring.service;

import dev.sindic.enrollmenthub.geoscoring.libpostal.AddressComponent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NominatimGeocodingProviderTest {

    private static final List<AddressComponent> AMSTERDAM = List.of(
            new AddressComponent("city", "amsterdam"),
            new AddressComponent("house_number", "1"),
            new AddressComponent("road", "keizersgracht"),
            new AddressComponent("postcode", "1015cj"),
            new AddressComponent("country", "nl")
    );

    @Mock RestClient restClient;
    @Mock RestClient.RequestHeadersUriSpec<?> requestHeadersUriSpec;
    @Mock RestClient.RequestHeadersSpec<?> requestHeadersSpec;
    @Mock RestClient.ResponseSpec responseSpec;

    MeterRegistry meterRegistry;
    NominatimGeocodingProvider provider;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        provider = new NominatimGeocodingProvider(restClient, meterRegistry);
    }

    @Test
    void geocode_successfulResult_returnsCoordinates() {
        stubResults(List.of(new NominatimGeocodingProvider.NominatimResult("52.3676", "4.9041")));

        var result = provider.geocode(AMSTERDAM);

        assertThat(result).isPresent();
        assertThat(result.get().latitude()).isEqualTo(52.3676);
        assertThat(result.get().longitude()).isEqualTo(4.9041);
    }

    @Test
    void geocode_structuredEmpty_fallsBackToFreeFormQuery() {
        stubSequentialResults(
                List.of(),
                List.of(new NominatimGeocodingProvider.NominatimResult("52.3676", "4.9041"))
        );

        var result = provider.geocode(AMSTERDAM);

        assertThat(result).isPresent();
        assertThat(meterRegistry.counter("geocoding.fallback", "outcome", "found").count()).isEqualTo(1.0);
    }

    @Test
    void geocode_structuredEmpty_fallbackAlsoEmpty_returnsEmpty() {
        stubSequentialResults(List.of(), List.of());

        var result = provider.geocode(AMSTERDAM);

        assertThat(result).isEmpty();
        assertThat(meterRegistry.counter("geocoding.fallback", "outcome", "empty").count()).isEqualTo(1.0);
    }

    @Test
    void geocode_serverError_throwsTransient_andSkipsFallback() {
        stubError(new HttpServerErrorException(HttpStatus.BAD_GATEWAY, "Bad Gateway"));

        assertThatThrownBy(() -> provider.geocode(AMSTERDAM))
                .isInstanceOf(TransientGeocodingException.class)
                .hasCauseInstanceOf(HttpServerErrorException.class);

        // Fallback must not run when the structured query failed transiently.
        verify(requestHeadersSpec, times(1)).retrieve();
    }

    @Test
    void geocode_transportFailure_throwsTransient() {
        stubError(new ResourceAccessException("connection refused"));

        assertThatThrownBy(() -> provider.geocode(AMSTERDAM))
                .isInstanceOf(TransientGeocodingException.class)
                .hasCauseInstanceOf(ResourceAccessException.class);
    }

    @Test
    void geocode_rateLimited_throwsTransient() {
        stubError(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests"));

        assertThatThrownBy(() -> provider.geocode(AMSTERDAM))
                .isInstanceOf(TransientGeocodingException.class)
                .hasCauseInstanceOf(HttpClientErrorException.class);
    }

    @Test
    void geocode_badRequest_returnsEmpty() {
        // 4xx that isn't a transient signal → degrade to no-match, both attempts return empty.
        stubError(new HttpClientErrorException(HttpStatus.BAD_REQUEST, "Bad Request"));

        var result = provider.geocode(AMSTERDAM);

        assertThat(result).isEmpty();
        // Structured attempt returned empty, so fallback was attempted as well.
        verify(requestHeadersSpec, times(2)).retrieve();
    }

    @Test
    void geocode_unmappedLabel_logsAndIncrementsMetric() {
        stubResults(List.of(new NominatimGeocodingProvider.NominatimResult("52.3676", "4.9041")));

        provider.geocode(List.of(
                new AddressComponent("city", "amsterdam"),
                new AddressComponent("suburb", "jordaan")
        ));

        assertThat(meterRegistry.counter("geocoding.unmapped_label", "label", "suburb").count()).isEqualTo(1.0);
    }

    @Test
    void geocode_structuredQueryIncludesStreetWithHouseNumber() {
        stubResults(List.of(new NominatimGeocodingProvider.NominatimResult("52.3676", "4.9041")));
        var uriCaptor = ArgumentCaptor.forClass(String.class);

        provider.geocode(AMSTERDAM);

        verify(requestHeadersUriSpec).uri(uriCaptor.capture());
        assertThat(uriCaptor.getValue()).contains("street=1+keizersgracht");
    }

    @Test
    void geocode_fallbackQueryIsFreeForm() {
        stubSequentialResults(List.of(), List.of());
        var uriCaptor = ArgumentCaptor.forClass(String.class);

        provider.geocode(AMSTERDAM);

        verify(requestHeadersUriSpec, times(2)).uri(uriCaptor.capture());
        assertThat(uriCaptor.getAllValues().get(1)).contains("q=");
    }

    private void stubResults(List<NominatimGeocodingProvider.NominatimResult> results) {
        when(restClient.get()).thenReturn(cast(requestHeadersUriSpec));
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(cast(requestHeadersSpec));
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(any(ParameterizedTypeReference.class))).thenReturn(results);
    }

    private void stubSequentialResults(List<NominatimGeocodingProvider.NominatimResult> first,
                                        List<NominatimGeocodingProvider.NominatimResult> second) {
        when(restClient.get()).thenReturn(cast(requestHeadersUriSpec));
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(cast(requestHeadersSpec));
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(any(ParameterizedTypeReference.class))).thenReturn(first, second);
    }

    private void stubError(RuntimeException ex) {
        when(restClient.get()).thenReturn(cast(requestHeadersUriSpec));
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(cast(requestHeadersSpec));
        when(requestHeadersSpec.retrieve()).thenThrow(ex);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> T cast(Object o) {
        return (T) o;
    }
}
