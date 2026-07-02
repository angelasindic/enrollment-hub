package dev.sindic.enrollmenthub.authorizationserver.simulation;

import com.nimbusds.jose.jwk.RSAKey;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

/**
 * Wiring for the simulated payment-check prerequisite-token issuer: its own RSA signing key (a
 * distinct {@code kid}) and a stateless resource-server filter chain guarding the issuance endpoint
 * while leaving its JWKS public. The key is generated independently of the OIDC signing key so the
 * two trust roots never share material (ADR-03). The signing {@code JwtEncoder} is built privately
 * inside {@link PrerequisiteTokenService} — deliberately <em>not</em> a bean, since Spring
 * Authorization Server would otherwise adopt a {@code JwtEncoder} bean to sign its own OIDC tokens.
 */
@Configuration
@EnableConfigurationProperties(PaymentCheckProperties.class)
public class PaymentCheckConfiguration {

    /**
     * RSA key for the payment-check issuer — distinct from the OIDC signing key. Generated fresh per
     * startup (the verification key is served at {@code /payment-check/jwks}); load from a keystore
     * for a key stable across restarts, as for the OIDC key.
     */
    @Bean
    public RSAKey paymentCheckRsaKey() {
        KeyPair keyPair = generateRsaKey();
        return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();
    }

    /**
     * Guards {@code /payment-check/**}: the issuance endpoint requires a client-credentials access
     * token carrying scope {@code prerequisite:issue}; the JWKS endpoint is public so the
     * decision-engine can fetch the verification key. Stateless (bearer), so sessions and CSRF are off.
     * Ordered ahead of the form-login default chain so these API paths are not redirected to /login.
     */
    @Bean
    @Order(0)
    public SecurityFilterChain paymentCheckSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/payment-check/**")
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/payment-check/jwks").permitAll()
                        .anyRequest().hasAuthority("SCOPE_prerequisite:issue"))
                .csrf(AbstractHttpConfigurer::disable)
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
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
}
