package dev.sindic.enrollmenthub.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Calls the (simulated) payment-check issuer server-to-server to obtain a credit-card-check
 * attestation for a user. Authenticates with the {@code client_credentials} grant (registration
 * {@code payment-check}, scope {@code prerequisite:issue}) — no user token is relayed. The returned
 * JWT is a distinct trust root from the login token; the caller stores it in the user's session
 * (Step 3 / ADR-03).
 * <p>
 * The client_credentials {@code OAuth2AuthorizedClientManager} is built locally rather than exposed
 * as a bean, so it does not interfere with the request-scoped manager that {@code oauth2Login} +
 * {@code TokenRelay} use for the login client.
 */
@Component
public class PaymentCheckClient {

    private static final String REGISTRATION_ID = "payment-check";

    private final RestClient restClient;

    public PaymentCheckClient(ClientRegistrationRepository clientRegistrations,
                              @Value("${payment-check.issuer-uri}") String issuerUri) {
        OAuth2AuthorizedClientService authorizedClientService =
                new InMemoryOAuth2AuthorizedClientService(clientRegistrations);
        AuthorizedClientServiceOAuth2AuthorizedClientManager authorizedClientManager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(clientRegistrations, authorizedClientService);
        authorizedClientManager.setAuthorizedClientProvider(
                OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());

        OAuth2ClientHttpRequestInterceptor interceptor =
                new OAuth2ClientHttpRequestInterceptor(authorizedClientManager);
        interceptor.setClientRegistrationIdResolver(request -> REGISTRATION_ID);

        this.restClient = RestClient.builder()
                .baseUrl(issuerUri)
                .requestInterceptor(interceptor)
                .build();
    }

    /** Obtain a {@code credit_card_check} attestation bound to {@code subject}. */
    public String issueCreditCardCheck(String subject) {
        TokenResponse response = restClient.post()
                .uri("/credit-card")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("subject", subject))
                .retrieve()
                .body(TokenResponse.class);
        return response.token();
    }

    record TokenResponse(String token) {
    }
}
