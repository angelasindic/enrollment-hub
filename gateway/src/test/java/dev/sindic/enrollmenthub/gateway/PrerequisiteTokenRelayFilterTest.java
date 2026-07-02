package dev.sindic.enrollmenthub.gateway;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class PrerequisiteTokenRelayFilterTest {

    private final PrerequisiteTokenRelayFilter filter = new PrerequisiteTokenRelayFilter();

    @Test
    void attachesHeader_whenEnrollmentRequestAndSessionHoldsToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/enrollment/public/v1/enrollments");
        request.getSession().setAttribute(PaymentCheckController.PREREQUISITE_TOKEN_ATTRIBUTE, "the-jwt");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        HttpServletRequest forwarded = (HttpServletRequest) chain.getRequest();
        assertThat(forwarded.getHeader(PrerequisiteTokenRelayFilter.HEADER)).isEqualTo("the-jwt");
    }

    @Test
    void noHeader_whenSessionHasNoToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/enrollment/public/v1/enrollments");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        HttpServletRequest forwarded = (HttpServletRequest) chain.getRequest();
        assertThat(forwarded.getHeader(PrerequisiteTokenRelayFilter.HEADER)).isNull();
    }

    @Test
    void skipsNonEnrollmentPaths_evenWhenTokenPresent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.getSession().setAttribute(PaymentCheckController.PREREQUISITE_TOKEN_ATTRIBUTE, "the-jwt");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        HttpServletRequest forwarded = (HttpServletRequest) chain.getRequest();
        assertThat(forwarded.getHeader(PrerequisiteTokenRelayFilter.HEADER)).isNull();
    }
}
