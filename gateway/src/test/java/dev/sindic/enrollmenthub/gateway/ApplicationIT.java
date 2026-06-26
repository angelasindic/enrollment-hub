package dev.sindic.enrollmenthub.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gateway (authenticating edge) security. Runs offline: the OAuth2 client is configured with explicit
 * provider endpoints, so the context starts without the authorization-server being reachable.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApplicationIT {

    @Autowired
    MockMvc mockMvc;

    @Test
    void protectedRoute_whenUnauthenticated_redirectsToOauthLogin() throws Exception {
        mockMvc.perform(get("/enrollment/public/v1/enrollments"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/oauth2/authorization/enrollment-gateway"));
    }

    @Test
    void actuatorHealth_isPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
