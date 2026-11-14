package dev.sindic.enrollmenthub.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Gateway security: a stateful authenticating gateway (the edge).
 * <p>
 * The gateway logs the end user in against the authorization-server via OIDC
 * ({@code authorization_code}), holds the resulting session, and the {@code TokenRelay}
 * filter (configured in {@code application.yml}) forwards the user's access token to the
 * decision-engine on each proxied call. The gateway does not validate that access token:
 * signature, expiry and issuer are checked downstream at the decision-engine (resource server).
 * <p>
 * Besides routing it serves one endpoint of its own, {@code POST /payment-check}
 * ({@link PaymentCheckController}), and attaches the attestation obtained there to enrollment
 * requests ({@link PrerequisiteTokenRelayFilter}). It composes no responses from downstream
 * calls, so it is not a backend-for-frontend.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   ClientRegistrationRepository clientRegistrationRepository,
                                                   @Value("${idp.end-session-uri}") String endSessionUri) throws Exception {

        OidcClientInitiatedLogoutSuccessHandler logoutSuccessHandler = new OidcClientInitiatedLogoutSuccessHandler(
                withEndSessionEndpoint(clientRegistrationRepository, endSessionUri));
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
                        // dispatch is redirected to login and saved after all. An ERROR dispatch only renders
                        // the status the original request already ended with, so permitting it opens no route;
                        // a direct request to /error is a REQUEST dispatch and still needs authentication.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .anyRequest().authenticated()
                )
                // Unauthenticated requests trigger the authorization_code login against the
                // authorization-server. The session exists from the first redirect, because it holds the
                // pending authorization request, and becomes authenticated once the login completes.
                // A failed login redirects to /login?error, where the generated login page answers 200 with
                // the error message and the link that starts the login again. The failure handler is set
                // on the filter, not through failureHandler(): that method clears the configurer's failure
                // URL, the login page then no longer recognises /login?error, and the request is redirected
                // to login a second time without any error shown.
                .oauth2Login((login) -> login
                        .successHandler(new LoggingLoginSuccessHandler())
                        .withObjectPostProcessor(new ObjectPostProcessor<OAuth2LoginAuthenticationFilter>() {
                            @Override
                            public <F extends OAuth2LoginAuthenticationFilter> F postProcess(F filter) {
                                filter.setAuthenticationFailureHandler(new LoggingLoginFailureHandler());
                                return filter;
                            }
                        }))
                // SPA-friendly CSRF (Spring Security 7). spa() sets the readable XSRF-TOKEN cookie
                // repository and a request handler that accepts the raw cookie value echoed in
                // X-XSRF-TOKEN (past the default BREACH/XOR masking). That handler loads the token on
                // every request, so the cookie is written on a plain GET as well.
                .csrf(CsrfConfigurer::spa)
                // RP-initiated logout: clears the local session, then redirects the browser to the
                // authorization-server's end-session endpoint, which ends the session there and returns
                // the browser to the gateway.
                .logout((logout) -> logout
                        .logoutSuccessHandler(logoutSuccessHandler)
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                );

        return http.build();
    }

    /**
     * The registrations as the logout handler needs them: with the provider's end-session endpoint.
     * <p>
     * {@link OidcClientInitiatedLogoutSuccessHandler} reads that endpoint from the provider metadata of
     * the registration. The registrations here are built from explicit endpoints, without discovery, so
     * their metadata is empty; the handler then finds no endpoint and redirects to {@code /} instead,
     * which leaves the authorization-server session alive and logs the user straight back in.
     */
    private static ClientRegistrationRepository withEndSessionEndpoint(ClientRegistrationRepository registrations,
                                                                       String endSessionUri) {
        return (registrationId) -> {
            ClientRegistration registration = registrations.findByRegistrationId(registrationId);
            if (registration == null) {
                return null;
            }
            return ClientRegistration.withClientRegistration(registration)
                    .providerConfigurationMetadata(Map.of("end_session_endpoint", endSessionUri))
                    .build();
        };
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
     * error. Spring Security logs a failed callback only at TRACE, and the login page shows the same
     * generic message for every cause: a rejected client secret at the token endpoint, a lost
     * authorization request (session cookie dropped between redirect and callback), or an invalid ID
     * token all look identical without this line. The OAuth2 error carries codes and descriptions,
     * never tokens or secrets.
     * <p>
     * Code and description are not trusted text. On an error callback Spring Security copies them from
     * the {@code error} and {@code error_description} query parameters, and any client can start a login
     * to obtain the {@code state} such a callback needs. They are therefore logged with line breaks and
     * other control characters replaced and cut to {@value #MAX_LOGGED_LENGTH} characters, so a callback
     * can neither start a log line of its own nor write an arbitrarily long one.
     */
    static final class LoggingLoginFailureHandler extends SimpleUrlAuthenticationFailureHandler {

        static final int MAX_LOGGED_LENGTH = 200;

        /** Control characters (C0, C1) and the Unicode line and paragraph separators. */
        private static final Pattern LINE_BREAKING = Pattern.compile("[\\p{Cc}\\p{Zl}\\p{Zp}]");

        LoggingLoginFailureHandler() {
            super("/login?error");
        }

        @Override
        public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                            AuthenticationException exception) throws IOException, ServletException {
            if (exception instanceof OAuth2AuthenticationException oauth2) {
                OAuth2Error error = oauth2.getError();
                log.warn("Login failed error={} description={}",
                        loggable(error.getErrorCode()), loggable(error.getDescription()));
            } else {
                log.warn("Login failed: {}", loggable(exception.getMessage()));
            }
            super.onAuthenticationFailure(request, response, exception);
        }

        static String loggable(String value) {
            if (value == null) {
                return null;
            }
            String bounded = (value.length() > MAX_LOGGED_LENGTH)
                    ? value.substring(0, MAX_LOGGED_LENGTH) + "..." : value;
            return LINE_BREAKING.matcher(bounded).replaceAll("_");
        }
    }
}
