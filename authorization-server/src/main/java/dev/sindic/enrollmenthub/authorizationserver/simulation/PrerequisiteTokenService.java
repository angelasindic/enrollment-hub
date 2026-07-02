package dev.sindic.enrollmenthub.authorizationserver.simulation;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Mints the credit-card-check prerequisite attestation — a JWT signed by the payment-check key under
 * a distinct issuer. Simulates the signed result a payment provider (e.g. Adyen) would return after a
 * successful check; the decision-engine validates it as a second, independent trust root (ADR-03).
 */
@Service
public class PrerequisiteTokenService {

    static final String CREDIT_CARD_CHECK = "credit_card_check";

    private final JwtEncoder paymentCheckEncoder;
    private final PaymentCheckProperties properties;

    public PrerequisiteTokenService(RSAKey paymentCheckRsaKey, PaymentCheckProperties properties) {
        // Built privately rather than exposed as a JwtEncoder bean: Spring Authorization Server adopts
        // a JwtEncoder bean to sign its own OIDC tokens, which would route them through the
        // payment-check key. Keeping it local ensures only this issuer uses the payment-check key.
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(paymentCheckRsaKey));
        this.paymentCheckEncoder = new NimbusJwtEncoder(jwkSource);
        this.properties = properties;
    }

    /** Issue a {@code credit_card_check} attestation bound to {@code subject}. */
    public String issueCreditCardCheck(String subject) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(subject)
                .audience(List.of(properties.audience()))
                .issuedAt(now)
                .expiresAt(now.plus(properties.tokenTtl()))
                .claim("type", CREDIT_CARD_CHECK)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return paymentCheckEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
