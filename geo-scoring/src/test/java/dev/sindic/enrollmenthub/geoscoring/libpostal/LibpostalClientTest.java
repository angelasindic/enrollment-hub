package dev.sindic.enrollmenthub.geoscoring.libpostal;

import dev.sindic.enrollmenthub.geoscoring.service.TransientGeocodingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LibpostalClientTest {

    private static final String ADDRESS = "Keizersgracht 1, 1015 CJ Amsterdam, NL";

    @Mock RestClient restClient;
    @Mock RestClient.RequestHeadersUriSpec<?> requestHeadersUriSpec;
    @Mock RestClient.RequestHeadersSpec<?> requestHeadersSpec;
    @Mock RestClient.ResponseSpec responseSpec;

    LibpostalClient client;

    @BeforeEach
    void setUp() {
        client = new LibpostalClient(restClient);
    }

    @Test
    void parse_success_returnsComponents() {
        var components = List.of(
                new AddressComponent("road", "keizersgracht"),
                new AddressComponent("house_number", "1"),
                new AddressComponent("city", "amsterdam")
        );
        stubBody(components);

        assertThat(client.parse(ADDRESS)).isEqualTo(components);
    }

    @Test
    void parse_nullBody_returnsEmptyList() {
        stubBody(null);

        assertThat(client.parse(ADDRESS)).isEmpty();
    }

    @Test
    void parse_badRequest_returnsEmptyList() {
        stubError(new HttpClientErrorException(HttpStatus.BAD_REQUEST, "Bad Request"));

        assertThat(client.parse(ADDRESS)).isEmpty();
    }

    @Test
    void parse_rateLimited_throwsTransient() {
        stubError(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests"));

        assertThatThrownBy(() -> client.parse(ADDRESS))
                .isInstanceOf(TransientGeocodingException.class)
                .hasCauseInstanceOf(HttpClientErrorException.class);
    }

    @Test
    void parse_serverError_throwsTransient() {
        stubError(new HttpServerErrorException(HttpStatus.BAD_GATEWAY, "Bad Gateway"));

        assertThatThrownBy(() -> client.parse(ADDRESS))
                .isInstanceOf(TransientGeocodingException.class)
                .hasCauseInstanceOf(HttpServerErrorException.class);
    }

    @Test
    void parse_transportFailure_throwsTransient() {
        stubError(new ResourceAccessException("connection refused"));

        assertThatThrownBy(() -> client.parse(ADDRESS))
                .isInstanceOf(TransientGeocodingException.class)
                .hasCauseInstanceOf(ResourceAccessException.class);
    }

    private void stubBody(List<AddressComponent> body) {
        when(restClient.get()).thenReturn(cast(requestHeadersUriSpec));
        when(requestHeadersUriSpec.uri(anyString(), any(Object[].class))).thenReturn(cast(requestHeadersSpec));
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(any(ParameterizedTypeReference.class))).thenReturn(body);
    }

    private void stubError(RuntimeException ex) {
        when(restClient.get()).thenReturn(cast(requestHeadersUriSpec));
        when(requestHeadersUriSpec.uri(anyString(), any(Object[].class))).thenReturn(cast(requestHeadersSpec));
        when(requestHeadersSpec.retrieve()).thenThrow(ex);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> T cast(Object o) {
        return (T) o;
    }
}
