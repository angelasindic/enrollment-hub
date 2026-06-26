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
                        .with(httpBasic("enrollment-gateway", "wrong-secret")))
                .andExpect(status().isUnauthorized());
    }
}
