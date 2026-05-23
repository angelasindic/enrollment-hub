package dev.sindic.enrollmenthub.decisionengine.persistence;

import dev.sindic.enrollmenthub.decisionengine.domain.*;
import dev.sindic.enrollmenthub.decisionengine.TestEntityFactory;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
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
            assertThat(entity.getSignals().get(SignalConfig.GEO_SCORE).processingState())
                    .isEqualTo(SignalProcessingState.PENDING);
            assertThat(entity.getSignals().get(SignalConfig.FRAUD_CHECK).processingState())
                    .isEqualTo(SignalProcessingState.PENDING);
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
            assertThat(entity.getSignals().get(SignalConfig.FRAUD_CHECK).processingState())
                    .isEqualTo(SignalProcessingState.PENDING);
        }
    }

    @Nested
    class CompletionPredicate {

        @Test
        void creditCardNotCompleteAtCreation() {
            var entity = TestEntityFactory.creditCard(UUID.randomUUID(), NOW, TIMEOUT);
            assertThat(entity.isComplete()).isFalse();
        }

        @Test
        void invoiceNotCompleteAtCreation() {
            var entity = TestEntityFactory.invoice(UUID.randomUUID(), NOW, TIMEOUT);
            assertThat(entity.isComplete()).isFalse();
        }

        @Test
        void creditCardCompleteWhenBothSignalsSettle() {
            var entity = TestEntityFactory.creditCard(UUID.randomUUID(), NOW, TIMEOUT);
            // Direct map mutation is test-only state-setup; production code writes
            // via repository.updateSignals (ADR-015 §Write path).
            entity.getSignals().put(SignalConfig.GEO_SCORE, SignalState.settled(RiskLevel.LOW));
            entity.getSignals().put(SignalConfig.FRAUD_CHECK, SignalState.settled(SignalOutcome.OK));
            assertThat(entity.isComplete()).isTrue();
        }

        @Test
        void invoiceCompleteWhenFraudSettles() {
            var entity = TestEntityFactory.invoice(UUID.randomUUID(), NOW, TIMEOUT);
            entity.getSignals().put(SignalConfig.FRAUD_CHECK, SignalState.settled(SignalOutcome.OK));
            assertThat(entity.isComplete()).isTrue();
        }
    }

    // Write-path semantics (idempotency guard, decision-already-recorded guard) live
    // in the repository methods now (ADR-015 §Write path). See EnrollmentRepositoryIT$UpdateSignals
    // and EnrollmentRepositoryIT$RecordDecisionMethod for the corresponding tests.

    @Nested
    class ToDomain {

        @Test
        void creditCardConvertsToDomainModel() {
            var requestId = UUID.randomUUID();
            var entity = TestEntityFactory.creditCard(requestId, NOW, TIMEOUT);
            var command = creditCardCommand();

            var process = entity.toDomain(command);

            assertThat(process.requestId()).isEqualTo(requestId);
            assertThat(process.signals()).containsOnlyKeys(SignalConfig.GEO_SCORE, SignalConfig.FRAUD_CHECK);
            assertThat(process.createdAt()).isEqualTo(NOW);
            assertThat(process.timeoutAt()).isEqualTo(TIMEOUT);
        }

        @Test
        void invoiceConvertsToDomainModel() {
            var requestId = UUID.randomUUID();
            var entity = TestEntityFactory.invoice(requestId, NOW, TIMEOUT);

            var process = entity.toDomain(invoiceCommand());

            assertThat(process.signals()).containsOnlyKeys(SignalConfig.FRAUD_CHECK);
        }
    }

    private static EnrollmentCommand creditCardCommand() {
        return new EnrollmentCommand(UUID.randomUUID(), PaymentType.CREDIT_CARD,
                new Person("Ada", "Lovelace", "ada@example.com", "+49123"),
                new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE"),
                new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE"));
    }

    private static EnrollmentCommand invoiceCommand() {
        return new EnrollmentCommand(UUID.randomUUID(), PaymentType.INVOICE,
                new Person("Ada", "Lovelace", "ada@example.com", "+49123"),
                new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE"),
                new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE"));
    }
}
