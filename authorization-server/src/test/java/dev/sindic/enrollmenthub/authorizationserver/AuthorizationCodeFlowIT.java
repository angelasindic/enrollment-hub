package dev.sindic.enrollmenthub.authorizationserver;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the full authorization_code flow end-to-end through MockMvc: form login with the seeded
 * BCrypt user, PKCE authorization request, consent approval, and the code-for-token exchange.
 * <p>
 * The claim assertions pin down the contract the decision-engine's bearer validation relies on
 * (ADR-03): {@code iss} matches the fixed issuer and {@code aud} is {@code enrollment-api}, stamped
 * by the access-token customizer on {@code enrollment:write} tokens. The guard tests prove the
 * customizer's scope and token-type checks: the {@code prerequisite:issue} machine token and the
 * OIDC ID token keep their default audiences.
 */
@AutoConfigureMockMvc
class AuthorizationCodeFlowIT extends BaseIntegrationTest {

    private static final String REDIRECT_URI = "http://127.0.0.1:8079/login/oauth2/code/enrollment-gateway";

    /** The default SAS consent page carries the flow state in a hidden input. */
    private static final Pattern CONSENT_STATE = Pattern.compile("name=\"state\" value=\"([^\"]+)\"");

    @Autowired
    MockMvc mockMvc;

    private final JsonMapper jsonMapper = new JsonMapper();

    @Test
    void authorizationCodeFlow_issuesAccessTokenWithEnrollmentApiAudience() throws Exception {
        JsonNode tokenResponse = runAuthorizationCodeFlow();

        SignedJWT accessToken = SignedJWT.parse(tokenResponse.get("access_token").asText());
        assertThat(accessToken.getJWTClaimsSet().getIssuer()).isEqualTo("http://localhost:9000");
        assertThat(accessToken.getJWTClaimsSet().getSubject()).isEqualTo("user");
        assertThat(accessToken.getJWTClaimsSet().getStringListClaim("scope")).contains("enrollment:write");
        // The customizer stamps the resource audience the decision-engine validates against.
        assertThat(accessToken.getJWTClaimsSet().getAudience()).containsExactly("enrollment-api");
    }

    @Test
    void idToken_keepsClientAudience() throws Exception {
        JsonNode tokenResponse = runAuthorizationCodeFlow();

        // The customizer is guarded on token type: the OIDC ID token keeps aud = client_id
        // (an OIDC requirement) and must not pass the decision-engine's audience check.
        SignedJWT idToken = SignedJWT.parse(tokenResponse.get("id_token").asText());
        assertThat(idToken.getJWTClaimsSet().getAudience()).containsExactly("enrollment-login-client");
    }

    @Test
    void machineToken_withoutEnrollmentWrite_keepsDefaultAudience() throws Exception {
        MvcResult result = mockMvc.perform(post("/oauth2/token")
                        .param("grant_type", "client_credentials")
                        .param("scope", "prerequisite:issue")
                        .with(httpBasic("payment-check-client", "payment-check-client-secret")))
                .andExpect(status().isOk())
                .andReturn();

        // The customizer is guarded on the enrollment:write scope: the payment-check machine token
        // keeps its default audience, so it is rejected by the decision-engine's audience check.
        SignedJWT accessToken = SignedJWT.parse(readJson(result).get("access_token").asText());
        assertThat(accessToken.getJWTClaimsSet().getAudience()).doesNotContain("enrollment-api");
    }

    /** Login → authorize (PKCE) → consent → token exchange; returns the parsed token response. */
    private JsonNode runAuthorizationCodeFlow() throws Exception {
        MvcResult loginResult = mockMvc.perform(formLogin().user("user").password("password"))
                .andExpect(authenticated())
                .andReturn();
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        String codeVerifier = UUID.randomUUID() + "-" + UUID.randomUUID();

        // queryParam (not param): SAS parses the authorization request off the query string.
        // code_challenge: the authorization server mandates PKCE (OAuth 2.1 default).
        MvcResult authorizeResult = mockMvc.perform(get("/oauth2/authorize")
                        .queryParam("response_type", "code")
                        .queryParam("client_id", "enrollment-login-client")
                        .queryParam("redirect_uri", REDIRECT_URI)
                        .queryParam("scope", "openid profile enrollment:write")
                        .queryParam("state", "client-state")
                        .queryParam("code_challenge", s256(codeVerifier))
                        .queryParam("code_challenge_method", "S256")
                        .session(session))
                .andReturn();

        // requireAuthorizationConsent(true): the first pass renders the consent page (200) and the
        // approval redirects back with the code. The JDBC consent service persists the decision, so
        // a repeat authorization for the same user + client redirects immediately (302).
        String location;
        if (authorizeResult.getResponse().getStatus() == 200) {
            String consentState = consentState(authorizeResult.getResponse().getContentAsString());
            MvcResult consentResult = mockMvc.perform(post("/oauth2/authorize")
                            .param("client_id", "enrollment-login-client")
                            .param("state", consentState)
                            .param("scope", "profile")
                            .param("scope", "enrollment:write")
                            .session(session))
                    .andExpect(status().is3xxRedirection())
                    .andReturn();
            location = consentResult.getResponse().getRedirectedUrl();
        } else {
            assertThat(authorizeResult.getResponse().getStatus()).isEqualTo(302);
            location = authorizeResult.getResponse().getRedirectedUrl();
        }
        assertThat(location).startsWith(REDIRECT_URI).contains("state=client-state").contains("code=");
        String code = UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("code");

        MvcResult tokenResult = mockMvc.perform(post("/oauth2/token")
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("code_verifier", codeVerifier)
                        .with(httpBasic("enrollment-login-client", "enrollment-login-client-secret")))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(tokenResult);
    }

    private static String s256(String codeVerifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static String consentState(String consentHtml) {
        Matcher matcher = CONSENT_STATE.matcher(consentHtml);
        assertThat(matcher.find()).as("hidden state input on the consent page").isTrue();
        return matcher.group(1);
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }
}
