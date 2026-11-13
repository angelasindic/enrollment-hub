package dev.sindic.enrollmenthub.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.DispatcherType;
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

    private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

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
                        // Chrome requests this path whenever DevTools is open. Answered directly (404), it
                        // never reaches the login redirect, so it cannot replace the saved request that
                        // the gateway returns the user to after login.
                        .requestMatchers("/.well-known/appspecific/**").permitAll()
                        // The container renders that 404 through an ERROR dispatch to /error, which passes
                        // this filter chain again; it has to be permitted too, or the anonymous error
                        // dispatch is redirected to login and saved after all. An ERROR dispatch only follows
                        // a request that was already authorized.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .anyRequest().authenticated()
                )
                // Unauthenticated requests trigger the authorization_code login against the
                // authorization-server; a session cookie is issued once it completes.
                .oauth2Login((login) -> login
                        .successHandler(new LoggingLoginSuccessHandler())
                        .failureHandler(new LoggingLoginFailureHandler()))
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
     * The default success handler (redirect to the saved request), plus one INFO line per completed login.
     */
    static final class LoggingLoginSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {
        @Override
        public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                            Authentication authentication) throws ServletException, IOException {
            String registrationId = (authentication instanceof OAuth2AuthenticationToken token)
                    ? token.getAuthorizedClientRegistrationId() : null;
            log.info("Login succeeded registrationId={}", registrationId);
            super.onAuthenticationSuccess(request, response, authentication);
        }
    }

    /**
     * The default failure handler (redirect to {@code /login?error}), plus a WARN line with the OAuth2
     * error. Spring Security logs a failed callback only at DEBUG, and the login page shows the same
     * generic message for every cause: a rejected client secret at the token endpoint, a lost
     * authorization request (session cookie dropped between redirect and callback), or an invalid ID
     * token all look identical without this line. The OAuth2 error carries codes and descriptions,
     * never tokens or secrets.
     */
    static final class LoggingLoginFailureHandler extends SimpleUrlAuthenticationFailureHandler {

        LoggingLoginFailureHandler() {
            super("/login?error");
        }

        @Override
        public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                            AuthenticationException exception) throws IOException, ServletException {
            if (exception instanceof OAuth2AuthenticationException oauth2) {
                OAuth2Error error = oauth2.getError();
                log.warn("Login failed error={} description={}", error.getErrorCode(), error.getDescription());
            } else {
                log.warn("Login failed: {}", exception.getMessage());
            }
            super.onAuthenticationFailure(request, response, exception);
        }
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
