package dev.sindic.enrollmenthub.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gateway (authenticating edge) security. Runs offline: the OAuth2 client is configured with explicit
 * provider endpoints, so the context starts without the authorization-server being reachable.
 * <p>
 * Runs on a real port as well as MockMvc: MockMvc performs no ERROR dispatch, so a 404 that the container
 * renders through {@code /error} is only observable over HTTP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ENROLLMENT_LOGIN_CLIENT_SECRET=test-login-secret",
        "PAYMENT_CHECK_CLIENT_SECRET=test-payment-check-secret"})
@AutoConfigureMockMvc
@AutoConfigureRestTestClient
class ApplicationIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    RestTestClient restTestClient;

    @Test
    void protectedRoute_whenUnauthenticated_redirectsToOauthLogin() throws Exception {
        mockMvc.perform(get("/enrollment/public/v1/enrollments"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/oauth2/authorization/enrollment-gateway"));
    }

    @Test
    void chromeDevToolsProbe_isNotRedirectedToLogin() {
        // A redirect to login would save this request as the post-login target.
        restTestClient.get().uri("/.well-known/appspecific/com.chrome.devtools.json")
                .accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void failedLoginCallback_redirectsToLoginError() throws Exception {
        // No authorization request in the session: the callback fails with authorization_request_not_found
        // and the logging failure handler keeps the default redirect.
        mockMvc.perform(get("/login/oauth2/code/enrollment-gateway").param("code", "c").param("state", "s"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void actuatorHealth_isPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
