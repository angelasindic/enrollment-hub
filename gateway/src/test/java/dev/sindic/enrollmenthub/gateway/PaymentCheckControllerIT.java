package dev.sindic.enrollmenthub.gateway;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the gateway payment-check step (Step 3): an authenticated user's POST triggers the
 * server-to-server attestation fetch and stores the resulting JWT in the session; unauthenticated
 * calls are redirected to login.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentCheckControllerIT {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    PaymentCheckClient paymentCheckClient;

    @Test
    void storesAttestationInSession() throws Exception {
        given(paymentCheckClient.issueCreditCardCheck("user")).willReturn("the-credit-card-jwt");

        MvcResult result = mockMvc.perform(post("/payment-check")
                        .with(oidcLogin().idToken(token -> token.subject("user")))
                        .with(csrf()))
                .andExpect(status().isNoContent())
                .andReturn();

        HttpSession session = result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(session.getAttribute(PaymentCheckController.PREREQUISITE_TOKEN_ATTRIBUTE))
                .isEqualTo("the-credit-card-jwt");
    }

    @Test
    void unauthenticated_isRedirectedToLogin() throws Exception {
        mockMvc.perform(post("/payment-check").with(csrf()))
                .andExpect(status().is3xxRedirection());
    }
}
