package dev.sindic.enrollmenthub.gateway;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

    @Autowired
    ClientRegistrationRepository clientRegistrations;

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
    @ExtendWith(OutputCaptureExtension.class)
    void failedLoginCallback_logsCallbackParametersOnOneLine(CapturedOutput output) throws Exception {
        // Any client can start a login to obtain a valid state, and then chooses error and
        // error_description of its own callback. Neither may break out of the log line.
        var session = new MockHttpSession();
        String authorizationRedirect = mockMvc
                .perform(get("/oauth2/authorization/enrollment-gateway").session(session))
                .andExpect(status().isFound())
                .andReturn().getResponse().getRedirectedUrl();
        String state = URLDecoder.decode(UriComponentsBuilder.fromUriString(authorizationRedirect)
                .build().getQueryParams().getFirst("state"), StandardCharsets.UTF_8);

        mockMvc.perform(get("/login/oauth2/code/enrollment-gateway").session(session)
                        .param("state", state)
                        .param("error", "access_denied")
                        .param("error_description", "denied\n2026-01-01 00:00:00 INFO  forged line"))
                .andExpect(redirectedUrl("/login?error"));

        assertThat(output.getAll())
                .contains("Login failed error=access_denied description=denied_2026-01-01 00:00:00 INFO  forged line")
                .doesNotContain("\n2026-01-01 00:00:00 INFO  forged line");
    }

    @Test
    void loginErrorPage_isRenderedForAnUnauthenticatedUser() throws Exception {
        // The target of the failure redirect. It is served by the generated login page, which only
        // recognises the URL while the oauth2Login configurer still holds its failure URL.
        mockMvc.perform(get("/login?error"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("alert-danger")));
    }

    @Test
    void plainGet_issuesTheReadableXsrfCookie() throws Exception {
        // A browser client needs the token before its first state-changing request.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, startsWith("XSRF-TOKEN=")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, not(containsString("HttpOnly"))));
    }

    @Test
    void stateChangingRequest_withoutTheXsrfHeader_isRejected() throws Exception {
        mockMvc.perform(post("/payment-check").with(oidcLogin()))
                .andExpect(status().isForbidden());
    }

    @Test
    void logout_redirectsToTheEndSessionEndpointOfTheAuthorizationServer() throws Exception {
        // The CSRF token travels as a browser sends it: the XSRF-TOKEN cookie, echoed in X-XSRF-TOKEN.
        String xsrf = mockMvc.perform(get("/actuator/health"))
                .andReturn().getResponse().getCookie("XSRF-TOKEN").getValue();

        String redirect = mockMvc.perform(post("/logout")
                        .with(oidcLogin().clientRegistration(
                                clientRegistrations.findByRegistrationId("enrollment-gateway")))
                        .cookie(new Cookie("XSRF-TOKEN", xsrf))
                        .header("X-XSRF-TOKEN", xsrf))
                .andExpect(status().isFound())
                .andReturn().getResponse().getRedirectedUrl();

        // Without the end-session endpoint the handler redirects to "/" and the
        // authorization-server session survives the logout.
        var target = UriComponentsBuilder.fromUriString(redirect).build();
        assertThat(target.getHost()).isEqualTo("localhost");
        assertThat(target.getPort()).isEqualTo(9000);
        assertThat(target.getPath()).isEqualTo("/connect/logout");
        assertThat(target.getQueryParams().getFirst("id_token_hint")).isNotBlank();
        assertThat(target.getQueryParams().getFirst("post_logout_redirect_uri")).isEqualTo("http://localhost");
    }

    @Test
    void actuatorHealth_isPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
