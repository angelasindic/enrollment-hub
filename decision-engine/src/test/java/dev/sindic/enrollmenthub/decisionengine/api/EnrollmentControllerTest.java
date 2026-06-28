package dev.sindic.enrollmenthub.decisionengine.api;

import dev.sindic.enrollmenthub.decisionengine.config.SecurityConfiguration;
import dev.sindic.enrollmenthub.decisionengine.domain.PendingEnrollmentResponse;
import dev.sindic.enrollmenthub.decisionengine.service.EnrollmentIntakeService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EnrollmentController.class)
@Import(SecurityConfiguration.class)
class EnrollmentControllerTest {

    private static final String ENDPOINT = "/enrollment/public/v1/enrollments";
    private static final String UUID_REGEX =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    /** A bearer token carrying the scope the endpoint demands. */
    private static RequestPostProcessor writeScope() {
        return jwt().authorities(new SimpleGrantedAuthority("SCOPE_enrollment:write"));
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    EnrollmentIntakeService enrollmentService;

    /** Satisfies the resource-server filter chain without reaching the real JWKS endpoint. */
    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void returns202WithEnrollmentId() throws Exception {
        UUID enrollmentId = UUID.randomUUID();
        given(enrollmentService.receiveEnrollment(any()))
                .willReturn(new PendingEnrollmentResponse(enrollmentId.toString()));

        mockMvc.perform(post(ENDPOINT)
                        .with(writeScope())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validBody())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.enrollmentId").value(matchesPattern(UUID_REGEX)));
    }

    @Test
    void returns401WhenNoToken() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validBody())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returns403WhenScopeMissing() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_other")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validBody())))
                .andExpect(status().isForbidden());
    }

    @Test
    void returns400WhenPaymentTypeIsMissing() throws Exception {
        Map<String, Object> body = validBody();
        body.remove("paymentType");

        mockMvc.perform(post(ENDPOINT)
                        .with(writeScope())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("paymentType")));
    }

    @Test
    void returns400WhenPersonIsMissing() throws Exception {
        Map<String, Object> body = validBody();
        body.remove("person");

        mockMvc.perform(post(ENDPOINT)
                        .with(writeScope())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("person")));
    }

    @Test
    void returns400WhenEmailAddressIsBlank() throws Exception {
        Map<String, Object> body = validBody();
        body.put("person", Map.of(
                "firstName", "Ada",
                "lastName", "Lovelace",
                "emailAddress", "",
                "phoneNumber", "+49123"));

        mockMvc.perform(post(ENDPOINT)
                        .with(writeScope())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("person.emailAddress")));
    }

    @Test
    void returns400WhenCountryCodeIsBlank() throws Exception {
        Map<String, Object> body = validBody();
        body.put("shippingAddress", Map.of(
                "streetLines", List.of("1 Main St"),
                "postalCode", "10115",
                "city", "Berlin",
                "subregion", "BE",
                "countryCode", ""));

        mockMvc.perform(post(ENDPOINT)
                        .with(writeScope())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("shippingAddress.countryCode")));
    }

    @Test
    void returns400ForEmptyBody() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .with(writeScope())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    private static Map<String, Object> validBody() {
        Map<String, Object> address = Map.of(
                "streetLines", List.of("1 Main St"),
                "postalCode", "10115",
                "city", "Berlin",
                "subregion", "BE",
                "countryCode", "DE"
        );
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("paymentType", "CREDIT_CARD");
        body.put("person", Map.of(
                "firstName", "Ada",
                "lastName", "Lovelace",
                "emailAddress", "ada@example.com",
                "phoneNumber", "+49123"));
        body.put("shippingAddress", address);
        body.put("billingAddress", address);
        return body;
    }
}
