package dev.sindic.enrollmenthub.decisionengine.persistence;

import dev.sindic.enrollmenthub.decisionengine.domain.*;
import dev.sindic.enrollmenthub.decisionengine.TestEntityFactory;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EnrollmentEntityTest {

    private static final Instant NOW = Instant.parse("2026-04-09T12:00:00Z");
    private static final Instant TIMEOUT = NOW.plusSeconds(60);

    @Nested
    class CreditCardFactory {

        @Test
        void initializesOnlyApplicableSignals() {
            var entity = TestEntityFactory.creditCard(UUID.randomUUID(), NOW, TIMEOUT);

            assertThat(entity.getPaymentType()).isEqualTo(PaymentType.CREDIT_CARD);
            assertThat(entity.getSignals()).containsOnlyKeys(SignalConfig.GEO_SCORE, SignalConfig.FRAUD_CHECK);
            assertThat(entity.getSignals().get(SignalConfig.GEO_SCORE)).isInstanceOf(SignalState.Pending.class);
            assertThat(entity.getSignals().get(SignalConfig.FRAUD_CHECK)).isInstanceOf(SignalState.Pending.class);
        }

        @Test
        void noDecisionAtCreation() {
            var entity = TestEntityFactory.creditCard(UUID.randomUUID(), NOW, TIMEOUT);

            assertThat(entity.getDecisionResult()).isNull();
            assertThat(entity.getDecidedAt()).isNull();
        }

        @Test
        void storesTimestamps() {
            var entity = TestEntityFactory.creditCard(UUID.randomUUID(), NOW, TIMEOUT);

            assertThat(entity.getCreatedAt()).isEqualTo(NOW);
            assertThat(entity.getTimeoutAt()).isEqualTo(TIMEOUT);
        }
    }

    @Nested
    class InvoiceFactory {

        @Test
        void initializesOnlyFraudCheck() {
            var entity = TestEntityFactory.invoice(UUID.randomUUID(), NOW, TIMEOUT);

            assertThat(entity.getPaymentType()).isEqualTo(PaymentType.INVOICE);
            assertThat(entity.getSignals()).containsOnlyKeys(SignalConfig.FRAUD_CHECK);
            assertThat(entity.getSignals().get(SignalConfig.FRAUD_CHECK)).isInstanceOf(SignalState.Pending.class);
        }
    }

    // Write-path semantics (idempotency guard, decision-already-recorded guard) live
    // in the repository methods now (ADR-16 §Write path). See EnrollmentRepositoryIT$UpdateSignals
    // and EnrollmentRepositoryIT$RecordDecisionMethod for the corresponding tests.
}
