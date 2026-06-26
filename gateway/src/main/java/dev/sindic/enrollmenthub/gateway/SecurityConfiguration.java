package dev.sindic.enrollmenthub.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

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
                // The downstream enrollment call is a POST, so expose a readable CSRF cookie
                // (XSRF-TOKEN) for browser/SPA clients rather than disabling CSRF on a stateful app.
                .csrf((csrf) -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
                // RP-initiated logout: clears the local session and ends the authorization-server session.
                .logout((logout) -> logout
                        .logoutSuccessHandler(logoutSuccessHandler)
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                );

        return http.build();
    }
}
