package dev.sindic.enrollmenthub.authorizationserver.simulation;

import dev.sindic.enrollmenthub.authorizationserver.BaseIntegrationTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the simulated payment-check issuer (Step 2 / ADR-03): a client-credentials caller with
 * scope {@code prerequisite:issue} mints a {@code credit_card_check} attestation signed under a
 * distinct issuer + key, published at a separate public JWKS.
 */
@AutoConfigureMockMvc
class PrerequisiteTokenIssuanceIT extends BaseIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    //private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonMapper jsonMapper = new JsonMapper();

    @Test
    void mintsCreditCardCheck_underDistinctIssuerAndKey() throws Exception {
        String accessToken = clientCredentialsToken();

        MvcResult result = mockMvc.perform(post("/payment-check/credit-card")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"user\"}"))
                .andExpect(status().isOk())
                .andReturn();

        SignedJWT jwt = SignedJWT.parse(readJson(result).get("token").asText());

        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("http://localhost:9000/payment-check");
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("user");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("type")).isEqualTo("credit_card_check");
        assertThat(jwt.getJWTClaimsSet().getAudience()).contains("enrollment-api");

        // The signing key is the payment-check key — distinct from the OIDC key and published at the
        // payment-check JWKS, never at /oauth2/jwks.
        assertThat(jwt.getHeader().getKeyID()).isNotIn(kids("/oauth2/jwks"));
        assertThat(kids("/payment-check/jwks")).contains(jwt.getHeader().getKeyID());
    }

    @Test
    void issuanceRejectsUnauthenticated() throws Exception {
        mockMvc.perform(post("/payment-check/credit-card")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"user\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void jwksIsPublic() throws Exception {
        mockMvc.perform(get("/payment-check/jwks")).andExpect(status().isOk());
    }

    private String clientCredentialsToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/oauth2/token")
                        .param("grant_type", "client_credentials")
                        .param("scope", "prerequisite:issue")
                        .with(httpBasic("payment-check-client", PAYMENT_CHECK_CLIENT_SECRET)))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).get("access_token").asText();
    }

    private List<String> kids(String jwksPath) throws Exception {
        JsonNode keys = readJson(mockMvc.perform(get(jwksPath)).andReturn()).get("keys");
        List<String> kids = new ArrayList<>();
        keys.forEach(key -> kids.add(key.get("kid").asText()));
        return kids;
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }
}
