package dev.sindic.enrollmenthub.authorizationserver;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the JDBC persistence hardening (ADR-05): Flyway applies cleanly, both OAuth2 clients are
 * seeded idempotently, the JdbcUserDetailsManager-backed user store resolves the seeded BCrypt user,
 * and an authorization round-trips through the Postgres-adapted oauth2_authorization table.
 */
class AuthorizationServerPersistenceIT extends BaseIntegrationTest {

    @Autowired
    RegisteredClientRepository registeredClientRepository;

    @Autowired
    OAuth2AuthorizationService authorizationService;

    @Autowired
    UserDetailsService userDetailsService;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    ApplicationRunner registeredClientSeeder;

    @Test
    void bothClients_areSeeded_andSeederIsIdempotent() throws Exception {
        assertThat(registeredClientRepository.findByClientId("enrollment-login-client")).isNotNull();
        assertThat(registeredClientRepository.findByClientId("payment-check-client")).isNotNull();
        assertThat(clientRowCount()).isEqualTo(2);

        // Re-running the seeder must not create duplicates (the guard is findByClientId per client).
        registeredClientSeeder.run(null);

        assertThat(clientRowCount()).isEqualTo(2);
    }

    @Test
    void seededUser_resolvesFromJdbcStore_withBcryptPassword() {
        UserDetails user = userDetailsService.loadUserByUsername("user");

        assertThat(user.getPassword()).startsWith("{bcrypt}");
        assertThat(passwordEncoder.matches("password", user.getPassword())).isTrue();
        assertThat(passwordEncoder.matches("wrong", user.getPassword())).isFalse();
        assertThat(user.getAuthorities()).extracting("authority").contains("ROLE_USER");
    }

    @Test
    void authorization_roundTripsThroughPostgres() {
        RegisteredClient client = registeredClientRepository.findByClientId("enrollment-login-client");
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, "access-token-value",
                Instant.now(), Instant.now().plusSeconds(300));
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .principalName("user")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .accessToken(accessToken)
                .build();

        authorizationService.save(authorization);

        assertThat(authorizationService.findById(authorization.getId())).isNotNull();
        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from oauth2_authorization where id = ?", Integer.class, authorization.getId());
        assertThat(rows).isEqualTo(1);
    }

    private Integer clientRowCount() {
        return jdbcTemplate.queryForObject("select count(*) from oauth2_registered_client", Integer.class);
    }
}
