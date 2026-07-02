package dev.sindic.enrollmenthub.authorizationserver.simulation;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Simulated payment-check (credit-card) prerequisite endpoints.
 * <p>
 * {@code POST /payment-check/credit-card} stands in for a payment provider confirming a successful
 * check and returns a signed attestation; it requires a client-credentials token with scope
 * {@code prerequisite:issue}. {@code GET /payment-check/jwks} publishes the public verification key
 * for the decision-engine — a JWKS separate from the OIDC {@code /oauth2/jwks}.
 */
@RestController
@RequestMapping("/payment-check")
public class PaymentCheckController {

    private final PrerequisiteTokenService prerequisiteTokenService;
    private final RSAKey paymentCheckRsaKey;

    public PaymentCheckController(PrerequisiteTokenService prerequisiteTokenService, RSAKey paymentCheckRsaKey) {
        this.prerequisiteTokenService = prerequisiteTokenService;
        this.paymentCheckRsaKey = paymentCheckRsaKey;
    }

    /** Subject the attestation is bound to — supplied by the caller (the gateway, for the logged-in user). */
    public record CreditCardCheckRequest(String subject) {
    }

    @PostMapping("/credit-card")
    public Map<String, String> issueCreditCardCheck(@RequestBody CreditCardCheckRequest request) {
        return Map.of("token", prerequisiteTokenService.issueCreditCardCheck(request.subject()));
    }

    @GetMapping("/jwks")
    public Map<String, Object> jwks() {
        return new JWKSet(paymentCheckRsaKey.toPublicJWK()).toJSONObject();
    }
}
