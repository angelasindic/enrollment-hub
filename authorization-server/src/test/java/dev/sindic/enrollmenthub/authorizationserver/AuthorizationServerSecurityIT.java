package dev.sindic.enrollmenthub.authorizationserver;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AuthorizationServerSecurityIT extends BaseIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void openidConfiguration_isPublishedWithExpectedIssuer() throws Exception {
        mockMvc.perform(get("/.well-known/openid-configuration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value("http://localhost:9000"));
    }

    @Test
    void jwks_isPublic() throws Exception {
        mockMvc.perform(get("/oauth2/jwks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys").isArray());
    }

    @Test
    void tokenEndpoint_withInvalidClientSecret_isUnauthorized() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .param("grant_type", "client_credentials")
                        .with(httpBasic("payment-check-client", "wrong-secret")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void machineClient_cannotRequestEnrollmentWriteScope() throws Exception {
        // Regression for the confused-deputy split (ADR-03): the client_credentials machine client is
        // registered for prerequisite:issue only, so requesting enrollment:write must be rejected —
        // a leaked machine secret cannot mint an enrollment-write token with no user login.
        mockMvc.perform(post("/oauth2/token")
                        .param("grant_type", "client_credentials")
                        .param("scope", "enrollment:write")
                        .with(httpBasic("payment-check-client", "payment-check-client-secret")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_scope"));
    }

    @Test
    void loginClient_cannotUseClientCredentialsGrant() throws Exception {
        // The login client is authorization_code only; it must not obtain a user-less token via the
        // client_credentials grant.
        mockMvc.perform(post("/oauth2/token")
                        .param("grant_type", "client_credentials")
                        .param("scope", "enrollment:write")
                        .with(httpBasic("enrollment-login-client", "enrollment-login-client-secret")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unauthorized_client"));
    }
}
