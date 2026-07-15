package dev.sindic.enrollmenthub.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Gateway security: a stateful authenticating gateway (the edge).
 * <p>
 * The gateway logs the end user in against the authorization-server via OIDC
 * ({@code authorization_code}), holds the resulting session, and the {@code TokenRelay}
 * filter (configured in {@code application.yml}) forwards the user's access token to the
 * decision-engine on each proxied call. It only routes — JWT signature/expiry/issuer
 * validation happens downstream at the decision-engine (resource server) — and composes no
 * responses, so it is not a backend-for-frontend.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   ClientRegistrationRepository clientRegistrationRepository) throws Exception {

        OidcClientInitiatedLogoutSuccessHandler logoutSuccessHandler =
                new OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository);
        // {baseUrl} resolves per-request to the gateway's public origin (scheme/host/port).
        logoutSuccessHandler.setPostLogoutRedirectUri("{baseUrl}");

        http
                .authorizeHttpRequests((authorize) -> authorize
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                        .anyRequest().authenticated()
                )
                // Unauthenticated requests trigger the authorization_code login against the
                // authorization-server; a session cookie is issued once it completes.
                .oauth2Login(Customizer.withDefaults())
                // SPA-friendly CSRF (Spring Security 7). spa() sets the readable XSRF-TOKEN cookie
                // repository and the SpaCsrfTokenRequestHandler that accepts the raw cookie value echoed
                // in X-XSRF-TOKEN (past the default BREACH/XOR masking). It does NOT write the cookie on a
                // GET — the token is resolved lazily — so CsrfCookieFilter forces resolution and the
                // cookie is issued after login.
                .csrf(CsrfConfigurer::spa)
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                // RP-initiated logout: clears the local session and ends the authorization-server session.
                .logout((logout) -> logout
                        .logoutSuccessHandler(logoutSuccessHandler)
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                );

        return http.build();
    }

    /**
     * Resolves the deferred CSRF token on every request so {@code spa()}'s readable XSRF-TOKEN cookie is
     * actually written, even on a plain GET. Runs after {@link CsrfFilter}, which by then has placed the
     * deferred token in the {@code _csrf} request attribute; calling {@code getToken()} forces
     * {@code CookieCsrfTokenRepository.saveToken} to emit {@code Set-Cookie: XSRF-TOKEN}. Without it the
     * cookie is absent until a state-changing request — leaving a browser/SPA client with no token to
     * echo right after login.
     */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute("_csrf");
            if (csrfToken != null) {
                csrfToken.getToken();
            }
            filterChain.doFilter(request, response);
        }
    }
}
