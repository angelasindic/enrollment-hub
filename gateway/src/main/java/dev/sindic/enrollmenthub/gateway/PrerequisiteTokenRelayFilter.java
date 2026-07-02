package dev.sindic.enrollmenthub.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Relays the session-held credit-card-check attestation to the decision-engine: on
 * {@code /enrollment/**} requests it attaches the token as the {@code X-Prerequisite-Token} header,
 * so the gateway forwards it alongside the {@code TokenRelay}'d access token (Step 3). It runs only
 * when the session holds a token; otherwise the request passes through and the decision-engine
 * enforces presence per payment type.
 */
@Component
public class PrerequisiteTokenRelayFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Prerequisite-Token";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        Object token = (session != null) ? session.getAttribute(PaymentCheckController.PREREQUISITE_TOKEN_ATTRIBUTE) : null;
        if (token instanceof String prerequisiteToken) {
            chain.doFilter(new PrerequisiteTokenRequest(request, prerequisiteToken), response);
        } else {
            chain.doFilter(request, response);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/enrollment/");
    }

    /** Wraps the request to expose {@code X-Prerequisite-Token} so the gateway forwards it downstream. */
    static final class PrerequisiteTokenRequest extends HttpServletRequestWrapper {

        private final String token;

        PrerequisiteTokenRequest(HttpServletRequest request, String token) {
            super(request);
            this.token = token;
        }

        @Override
        public String getHeader(String name) {
            return HEADER.equalsIgnoreCase(name) ? token : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return HEADER.equalsIgnoreCase(name) ? Collections.enumeration(List.of(token)) : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames());
            if (names.stream().noneMatch(HEADER::equalsIgnoreCase)) {
                names.add(HEADER);
            }
            return Collections.enumeration(names);
        }
    }
}
