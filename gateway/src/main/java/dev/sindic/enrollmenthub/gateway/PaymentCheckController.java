package dev.sindic.enrollmenthub.gateway;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The gateway-local "payment check" step (Step 3): after login, the SPA POSTs here; the gateway
 * fetches a credit-card-check attestation from the payment-check issuer server-to-server and stores
 * it in the user's session, to be relayed to the decision-engine at enrollment. The browser only ever
 * holds the session cookie — the prerequisite JWT stays server-side (BFF custody).
 * <p>
 * Simulation seam: in production the user submits card details to the provider (e.g. Adyen) and the
 * backend learns the result via webhook; here this endpoint stands in for "the check completed →
 * fetch the attestation".
 */
@RestController
public class PaymentCheckController {

    /** Session attribute holding the credit-card-check JWT until the enrollment request. */
    public static final String PREREQUISITE_TOKEN_ATTRIBUTE =
            PaymentCheckController.class.getName() + ".PREREQUISITE_TOKEN";

    private final PaymentCheckClient paymentCheckClient;

    public PaymentCheckController(PaymentCheckClient paymentCheckClient) {
        this.paymentCheckClient = paymentCheckClient;
    }

    @PostMapping("/payment-check")
    public ResponseEntity<Void> creditCardCheck(@AuthenticationPrincipal OidcUser user, HttpSession session) {
        String attestation = paymentCheckClient.issueCreditCardCheck(user.getSubject());
        session.setAttribute(PREREQUISITE_TOKEN_ATTRIBUTE, attestation);
        return ResponseEntity.noContent().build();
    }
}
