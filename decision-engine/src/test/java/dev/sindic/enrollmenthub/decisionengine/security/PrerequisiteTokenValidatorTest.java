package dev.sindic.enrollmenthub.decisionengine.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Unit-tests the prerequisite contract checks (type + subject binding) against a mocked decoder, so
 * the standard JWT validation (signature/issuer/audience/expiry) is exercised separately by the
 * decoder and not duplicated here.
 */
class PrerequisiteTokenValidatorTest {

    private final JwtDecoder decoder = mock(JwtDecoder.class);
    private final PrerequisiteTokenValidator validator = new PrerequisiteTokenValidator(decoder);

    private static Jwt jwt(String type, String subject) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("type", type)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }

    @Test
    void acceptsCreditCardCheckBoundToCaller() {
        given(decoder.decode("good")).willReturn(jwt("credit_card_check", "user"));

        assertThatCode(() -> validator.validateCreditCardCheck("good", "user")).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingToken() {
        assertThatThrownBy(() -> validator.validateCreditCardCheck(null, "user"))
                .isInstanceOf(PrerequisiteValidationException.class);
    }

    @Test
    void rejectsTokenThatFailsDecoding() {
        given(decoder.decode("bad")).willThrow(new BadJwtException("bad signature"));

        assertThatThrownBy(() -> validator.validateCreditCardCheck("bad", "user"))
                .isInstanceOf(PrerequisiteValidationException.class);
    }

    @Test
    void rejectsWrongType() {
        given(decoder.decode("eidas")).willReturn(jwt("eidas_identity", "user"));

        assertThatThrownBy(() -> validator.validateCreditCardCheck("eidas", "user"))
                .isInstanceOf(PrerequisiteValidationException.class);
    }

    @Test
    void rejectsSubjectMismatch() {
        given(decoder.decode("lifted")).willReturn(jwt("credit_card_check", "another-user"));

        assertThatThrownBy(() -> validator.validateCreditCardCheck("lifted", "user"))
                .isInstanceOf(PrerequisiteValidationException.class);
    }
}
