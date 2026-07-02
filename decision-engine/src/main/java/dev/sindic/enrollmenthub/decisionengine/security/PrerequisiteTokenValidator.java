package dev.sindic.enrollmenthub.decisionengine.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Validates the credit-card-check prerequisite token (ADR-03).
 * <p>
 * The token comes from the payment-check issuer — a trust root distinct from the bearer-token issuer —
 * so it is validated by its own decoder (signature against the payment-check JWKS, issuer, audience,
 * expiry), kept private here rather than exposed as a {@link JwtDecoder} bean so the resource server's
 * bearer decoder stays the sole one. On top of standard validation it enforces the prerequisite
 * contract: the {@code type} claim and subject binding to the authenticated caller (the confused-deputy
 * guard). Any failure is a 403.
 */
@Component
public class PrerequisiteTokenValidator {

    private static final Logger log = LoggerFactory.getLogger(PrerequisiteTokenValidator.class);
    static final String CREDIT_CARD_CHECK = "credit_card_check";

    private final JwtDecoder decoder;

    @Autowired
    public PrerequisiteTokenValidator(
            @Value("${prerequisite.tokens.credit-card.jwks-uri:http://localhost:9000/payment-check/jwks}") String jwksUri,
            @Value("${prerequisite.tokens.credit-card.issuer:http://localhost:9000/payment-check}") String issuer,
            @Value("${prerequisite.tokens.credit-card.audience:enrollment-api}") String audience) {
        this(buildDecoder(jwksUri, issuer, audience));
    }

    PrerequisiteTokenValidator(JwtDecoder decoder) {
        this.decoder = decoder;
    }

    private static JwtDecoder buildDecoder(String jwksUri, String issuer, String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtIssuerValidator(issuer),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.contains(audience))));
        return decoder;
    }

    /**
     * Validate a {@code credit_card_check} attestation for the authenticated caller.
     *
     * @throws PrerequisiteValidationException (403) if the token is absent, fails signature/issuer/
     *         audience/expiry validation, is not a {@code credit_card_check}, or is bound to another subject
     */
    public void validateCreditCardCheck(String token, String authenticatedSubject) {
        if (!StringUtils.hasText(token)) {
            throw reject("missing credit_card_check prerequisite token");
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(token);
        } catch (JwtException ex) {
            throw reject("credit_card_check prerequisite token failed validation");
        }
        if (!CREDIT_CARD_CHECK.equals(jwt.getClaimAsString("type"))) {
            throw reject("prerequisite token is not a credit_card_check");
        }
        if (!authenticatedSubject.equals(jwt.getSubject())) {
            throw reject("prerequisite token subject does not match the authenticated caller");
        }
    }

    private PrerequisiteValidationException reject(String reason) {
        log.warn("Prerequisite rejected: {}", reason);
        return new PrerequisiteValidationException(reason);
    }
}
