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
            entity.recordSignalResult(SignalConfig.GEO_SCORE, SignalState.settled(RiskLevel.LOW));
            entity.recordSignalResult(SignalConfig.FRAUD_CHECK, SignalState.settled(SignalOutcome.OK));
            assertThat(entity.isComplete()).isTrue();
        }

        @Test
        void invoiceCompleteWhenFraudSettles() {
            var entity = TestEntityFactory.invoice(UUID.randomUUID(), NOW, TIMEOUT);
            entity.recordSignalResult(SignalConfig.FRAUD_CHECK, SignalState.settled(SignalOutcome.OK));
            assertThat(entity.isComplete()).isTrue();
        }
    }

    @Nested
    class RecordSignalResult {

        @Test
        void updatesSignalState() {
            var entity = TestEntityFactory.creditCard(UUID.randomUUID(), NOW, TIMEOUT);
            var applied = entity.recordSignalResult(SignalConfig.GEO_SCORE, SignalState.settled(RiskLevel.HIGH));

            assertThat(applied).isTrue();
            assertThat(entity.getSignals().get(SignalConfig.GEO_SCORE).riskLevel()).isEqualTo(RiskLevel.HIGH);
        }

        @Test
        void idempotentOnDuplicate() {
            var entity = TestEntityFactory.creditCard(UUID.randomUUID(), NOW, TIMEOUT);
            entity.recordSignalResult(SignalConfig.GEO_SCORE, SignalState.settled(RiskLevel.LOW));

            var applied = entity.recordSignalResult(SignalConfig.GEO_SCORE, SignalState.settled(RiskLevel.HIGH));

            assertThat(applied).isFalse();
            assertThat(entity.getSignals().get(SignalConfig.GEO_SCORE).riskLevel()).isEqualTo(RiskLevel.LOW);
        }
    }

    @Nested
    class RecordDecision {

        @Test
        void setsDecisionResult() {
            var entity = TestEntityFactory.creditCard(UUID.randomUUID(), NOW, TIMEOUT);
            var applied = entity.recordDecision(DecisionResult.APPROVED, NOW.plusSeconds(5));

            assertThat(applied).isTrue();
            assertThat(entity.getDecisionResult()).isEqualTo(DecisionResult.APPROVED);
            assertThat(entity.getDecidedAt()).isEqualTo(NOW.plusSeconds(5));
        }

        @Test
        void idempotentIfAlreadyDecided() {
            var entity = TestEntityFactory.creditCard(UUID.randomUUID(), NOW, TIMEOUT);
            entity.recordDecision(DecisionResult.APPROVED, NOW.plusSeconds(5));

            var applied = entity.recordDecision(DecisionResult.REJECTED, NOW.plusSeconds(10));

            assertThat(applied).isFalse();
            assertThat(entity.getDecisionResult()).isEqualTo(DecisionResult.APPROVED);
        }
    }

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
        return new EnrollmentCommand(PaymentType.CREDIT_CARD,
                new Person("Ada", "Lovelace", "ada@example.com", "+49123"),
                new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE"),
                new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE"));
    }

    private static EnrollmentCommand invoiceCommand() {
        return new EnrollmentCommand(PaymentType.INVOICE,
                new Person("Ada", "Lovelace", "ada@example.com", "+49123"),
                new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE"),
                new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE"));
    }
}
