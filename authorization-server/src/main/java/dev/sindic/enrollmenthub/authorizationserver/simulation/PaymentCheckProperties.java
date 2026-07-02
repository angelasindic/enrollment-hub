package dev.sindic.enrollmenthub.authorizationserver.simulation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuration for the simulated payment-check (credit-card) prerequisite-token issuer — a trust
 * root kept fully separate from the OIDC issuer (distinct {@code iss}/{@code kid}/JWKS), co-located
 * in this deployable per ADR-03. Stands in for an external provider (e.g. Adyen) until a real
 * integration replaces it.
 */
@ConfigurationProperties("payment-check")
public record PaymentCheckProperties(
        @DefaultValue("http://localhost:9000/payment-check") String issuer,
        @DefaultValue("enrollment-api") String audience,
        @DefaultValue("10m") Duration tokenTtl) {
}
