package dev.sindic.enrollmenthub.authorizationserver;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import javax.sql.DataSource;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

/**
 * Spring Authorization Server configuration — the enrollment-hub IdP.
 * <p>
 * Issues OIDC/OAuth2 tokens to the {@code enrollment-gateway} login client (the gateway),
 * renders the login + consent screens, and publishes its signing keys at {@code /oauth2/jwks}
 * (the URL the decision-engine points its {@code jwk-set-uri} at).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    /**
     * Protects the authorization-server protocol endpoints (/oauth2/token, /oauth2/authorize,
     * /oauth2/jwks, the OIDC endpoints) and enables OpenID Connect.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServer = new OAuth2AuthorizationServerConfigurer();
        RequestMatcher endpointsMatcher = authorizationServer.getEndpointsMatcher();

        http
                .securityMatcher(endpointsMatcher)
                .with(authorizationServer, (server) -> server
                        .oidc(Customizer.withDefaults())
                )
                .authorizeHttpRequests((authorize) -> authorize
                        .anyRequest().authenticated()
                )
                // Protocol endpoints are machine-to-machine; CSRF would otherwise reject them.
                .csrf((csrf) -> csrf.ignoringRequestMatchers(endpointsMatcher))
                // A browser (text/html) hitting a protocol endpoint without a session is sent to /login.
                .exceptionHandling((exceptions) -> exceptions
                        .defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint("/login"),
                                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)
                        )
                );

        return http.build();
    }

    /**
     * Application security: renders the login form and authenticates the end user before the
     * authorization_code flow proceeds. Actuator health is left open for orchestration probes.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests((authorize) -> authorize
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                        .anyRequest().authenticated()
                )
                .formLogin(Customizer.withDefaults());

        return http.build();
    }

    /**
     * User store backed by the JDBC {@code users}/{@code authorities} tables (seeded by Flyway).
     * Replace with a real user store / identity federation outside the sandbox.
     */
    @Bean
    public UserDetailsService userDetailsService(DataSource dataSource) {
        return new JdbcUserDetailsManager(dataSource);
    }

    /**
     * Delegating encoder (reads the {@code {id}} prefix): validates the seeded {@code {bcrypt}}
     * user password and the {@code {noop}} client secret alike. SAS resolves this bean for
     * client-secret authentication, so a bare BCrypt encoder would reject the {@code {noop}} secret.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** Registered clients persisted in the {@code oauth2_registered_client} table. */
    @Bean
    public RegisteredClientRepository registeredClientRepository(JdbcOperations jdbcOperations) {
        return new JdbcRegisteredClientRepository(jdbcOperations);
    }

    /** Authorizations (codes, access/refresh tokens, flow state) persisted in {@code oauth2_authorization}. */
    @Bean
    public OAuth2AuthorizationService authorizationService(
            JdbcOperations jdbcOperations, RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationService(jdbcOperations, registeredClientRepository);
    }

    /** User consent decisions persisted in {@code oauth2_authorization_consent}. */
    @Bean
    public OAuth2AuthorizationConsentService authorizationConsentService(
            JdbcOperations jdbcOperations, RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationConsentService(jdbcOperations, registeredClientRepository);
    }

    /**
     * Seeds the single login client (the enrollment-hub gateway on :8079) into the persistent
     * repository on startup if absent. Idempotent across restarts; the DB row is the source of truth.
     * Secret is externalised via {@code GATEWAY_CLIENT_SECRET} (dev default for local runs).
     */
    @Bean
    public ApplicationRunner registeredClientSeeder(
            RegisteredClientRepository registeredClientRepository,
            @Value("${GATEWAY_CLIENT_SECRET:enrollment-secret}") String gatewaySecret) {
        return args -> {
            if (registeredClientRepository.findByClientId("enrollment-gateway") == null) {
                registeredClientRepository.save(gatewayClient(gatewaySecret));
            }
        };
    }

    private static RegisteredClient gatewayClient(String gatewaySecret) {
        return RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("enrollment-gateway")
                .clientSecret("{noop}" + gatewaySecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                // client_credentials: the gateway calls the payment-check issuer M2M (scope prerequisite:issue).
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .redirectUri("http://127.0.0.1:8079/login/oauth2/code/enrollment-gateway")
                // Must EXACTLY match the client's post_logout_redirect_uri ({baseUrl} -> no trailing slash).
                .postLogoutRedirectUri("http://127.0.0.1:8079")
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope("enrollment:write")
                .scope("prerequisite:issue")
                .clientSettings(ClientSettings.builder().requireAuthorizationConsent(true).build())
                .build();
    }

    /**
     * RSA key pair used to sign issued JWTs; the public part is served at /oauth2/jwks.
     * Generated fresh per startup — load from a keystore for a key stable across restarts.
     */
    @Bean
    public JWKSource<SecurityContext> jwkSource() {
        KeyPair keyPair = generateRsaKey();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        RSAKey rsaKey = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(UUID.randomUUID().toString())
                .build();
        JWKSet jwkSet = new JWKSet(rsaKey);
        return new ImmutableJWKSet<>(jwkSet);
    }

    private static KeyPair generateRsaKey() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            return keyPairGenerator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Bean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder()
                .issuer("http://localhost:9000")
                .build();
    }
}
