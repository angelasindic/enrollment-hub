package dev.sindic.enrollmenthub.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientSecretsCheckTest {

    @Test
    void passesWhenEverySecretIsSet() {
        var repository = new InMemoryClientRegistrationRepository(
                registration("login", "s3cret"), registration("payment-check", "0ther"));

        assertThatCode(() -> new ClientSecretsCheck(repository)).doesNotThrowAnyException();
    }

    @Test
    void failsOnUnresolvedPlaceholder_namingTheVariable() {
        var repository = new InMemoryClientRegistrationRepository(
                registration("login", "${ENROLLMENT_LOGIN_CLIENT_SECRET}"), registration("payment-check", "0ther"));

        assertThatThrownBy(() -> new ClientSecretsCheck(repository))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'login'")
                .hasMessageContaining("set ENROLLMENT_LOGIN_CLIENT_SECRET")
                .hasMessageNotContaining("payment-check");
    }

    @Test
    void failsOnBlankSecret() {
        var repository = new InMemoryClientRegistrationRepository(registration("login", " "));

        assertThatThrownBy(() -> new ClientSecretsCheck(repository))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'login' has a blank client-secret");
    }

    @Test
    void ignoresPublicClients() {
        var publicClient = ClientRegistration.withRegistrationId("public")
                .clientId("public-client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://127.0.0.1:8079/login/oauth2/code/public")
                .authorizationUri("http://localhost:9000/oauth2/authorize")
                .tokenUri("http://localhost:9000/oauth2/token")
                .build();

        assertThatCode(() -> new ClientSecretsCheck(new InMemoryClientRegistrationRepository(publicClient)))
                .doesNotThrowAnyException();
    }

    private static ClientRegistration registration(String id, String secret) {
        return ClientRegistration.withRegistrationId(id)
                .clientId(id + "-client")
                .clientSecret(secret)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri("http://localhost:9000/oauth2/token")
                .build();
    }
}
