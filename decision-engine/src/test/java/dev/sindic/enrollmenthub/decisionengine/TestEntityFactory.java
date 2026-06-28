package dev.sindic.enrollmenthub.decisionengine;

import dev.sindic.enrollmenthub.decisionengine.domain.PaymentType;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentEntity;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * Shared factory for tests that need {@link EnrollmentEntity} instances but
 * do not care about the {@code originalRequest} content.
 */
public final class TestEntityFactory {

    private static final JsonMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    // Minimal valid JSON for originalRequest in tests that don't assert on its content.
    // Templated so each entity carries its own enrollmentId, matching the EnrollmentData contract.
    private static final String ORIGINAL_REQUEST_TEMPLATE =
            "{\"enrollmentId\":\"%s\",\"paymentType\":\"%s\"," +
            "\"person\":{\"emailAddress\":\"test@example.com\"}," +
            "\"shippingAddress\":{\"countryCode\":\"DE\"}," +
            "\"billingAddress\":{\"countryCode\":\"DE\"}}";

    private TestEntityFactory() {}

    public static EnrollmentEntity creditCard(UUID enrollmentId, Instant createdAt, Instant timeoutAt) {
        return EnrollmentEntity.create(enrollmentId, PaymentType.CREDIT_CARD,
                originalRequestJson(enrollmentId, "CREDIT_CARD"), createdAt, timeoutAt);
    }

    public static EnrollmentEntity invoice(UUID enrollmentId, Instant createdAt, Instant timeoutAt) {
        return EnrollmentEntity.create(enrollmentId, PaymentType.INVOICE,
                originalRequestJson(enrollmentId, "INVOICE"), createdAt, timeoutAt);
    }

    private static String originalRequestJson(UUID enrollmentId, String paymentType) {
        return ORIGINAL_REQUEST_TEMPLATE.formatted(enrollmentId, paymentType);
    }
}
