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
    public static final String ANY_ORIGINAL_REQUEST =
            "{\"paymentType\":\"CREDIT_CARD\",\"person\":{\"emailAddress\":\"test@example.com\"}," +
            "\"shippingAddress\":{\"countryCode\":\"DE\"},\"billingAddress\":{\"countryCode\":\"DE\"}}";

    private TestEntityFactory() {}

    public static EnrollmentEntity creditCard(UUID requestId, Instant createdAt, Instant timeoutAt) {
        return EnrollmentEntity.create(requestId, PaymentType.CREDIT_CARD,
                ANY_ORIGINAL_REQUEST, createdAt, timeoutAt);
    }

    public static EnrollmentEntity invoice(UUID requestId, Instant createdAt, Instant timeoutAt) {
        return EnrollmentEntity.create(requestId, PaymentType.INVOICE,
                ANY_ORIGINAL_REQUEST, createdAt, timeoutAt);
    }
}
