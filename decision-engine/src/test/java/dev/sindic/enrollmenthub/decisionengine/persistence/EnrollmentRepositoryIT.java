package dev.sindic.enrollmenthub.decisionengine.persistence;

import dev.sindic.enrollmenthub.decisionengine.BaseIntegrationTest;
import dev.sindic.enrollmenthub.decisionengine.domain.*;
import dev.sindic.enrollmenthub.decisionengine.TestEntityFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EnrollmentRepositoryIT extends BaseIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-04-09T12:00:00Z");
    private static final Instant TIMEOUT = NOW.plusSeconds(60);

    @Autowired
    private EnrollmentRepository repository;

    @Nested
    class RoundTrip {

        @Test
        @Transactional
        void persistsAndRetrievesCreditCardEntity() {
            var requestId = UUID.randomUUID();
            var entity = TestEntityFactory.creditCard(requestId, NOW, TIMEOUT);

            repository.saveAndFlush(entity);
            var loaded = repository.findById(requestId).orElseThrow();

            assertThat(loaded.getRequestId()).isEqualTo(requestId);
            assertThat(loaded.getPaymentType()).isEqualTo(PaymentType.CREDIT_CARD);
            assertThat(loaded.getSignals()).containsOnlyKeys(SignalConfig.GEO_SCORE, SignalConfig.FRAUD_CHECK);
            assertThat(loaded.getSignals().get(SignalConfig.GEO_SCORE).processingState())
                    .isEqualTo(SignalProcessingState.PENDING);
            assertThat(loaded.getSignals().get(SignalConfig.FRAUD_CHECK).processingState())
                    .isEqualTo(SignalProcessingState.PENDING);
            assertThat(loaded.getCreatedAt()).isEqualTo(NOW);
            assertThat(loaded.getTimeoutAt()).isEqualTo(TIMEOUT);
        }

        @Test
        @Transactional
        void persistsAndRetrievesInvoiceEntity() {
            var requestId = UUID.randomUUID();
            var entity = TestEntityFactory.invoice(requestId, NOW, TIMEOUT);

            repository.saveAndFlush(entity);
            var loaded = repository.findById(requestId).orElseThrow();

            assertThat(loaded.getPaymentType()).isEqualTo(PaymentType.INVOICE);
            assertThat(loaded.getSignals()).containsOnlyKeys(SignalConfig.FRAUD_CHECK);
            assertThat(loaded.getSignals().get(SignalConfig.FRAUD_CHECK).processingState())
                    .isEqualTo(SignalProcessingState.PENDING);
        }
    }

    @Nested
    class ForUpdate {

        @Test
        @Transactional
        void acquiresRowLockWithoutError() {
            var requestId = UUID.randomUUID();
            repository.saveAndFlush(TestEntityFactory.creditCard(requestId, NOW, TIMEOUT));

            var locked = repository.findByRequestIdForUpdate(requestId);

            assertThat(locked).isPresent();
            assertThat(locked.get().getRequestId()).isEqualTo(requestId);
        }

        @Test
        @Transactional
        void returnsEmptyForNonexistentRequest() {
            assertThat(repository.findByRequestIdForUpdate(UUID.randomUUID())).isEmpty();
        }
    }

    @Nested
    class RecordSignalResult {

        private EnrollmentEntity entity;

        @BeforeEach
        void setUp() {
            entity = repository.saveAndFlush(
                    TestEntityFactory.creditCard(UUID.randomUUID(), NOW, TIMEOUT));
        }

        @Test
        @Transactional
        void updatesGeoScoreAndReturnsTrue() {
            boolean applied = entity.recordSignalResult(SignalConfig.GEO_SCORE,
                    SignalState.settled(RiskLevel.LOW));

            assertThat(applied).isTrue();
            assertThat(entity.getSignals().get(SignalConfig.GEO_SCORE).riskLevel()).isEqualTo(RiskLevel.LOW);
        }

        @Test
        @Transactional
        void doesNotTouchOtherSignals() {
            entity.recordSignalResult(SignalConfig.GEO_SCORE, SignalState.settled(RiskLevel.MEDIUM));

            assertThat(entity.getSignals().get(SignalConfig.FRAUD_CHECK).processingState())
                    .isEqualTo(SignalProcessingState.PENDING);
        }

        @Test
        @Transactional
        void idempotentOnDuplicateDelivery() {
            entity.recordSignalResult(SignalConfig.GEO_SCORE, SignalState.settled(RiskLevel.LOW));
            boolean second = entity.recordSignalResult(SignalConfig.GEO_SCORE, SignalState.settled(RiskLevel.HIGH));

            assertThat(second).isFalse();
            assertThat(entity.getSignals().get(SignalConfig.GEO_SCORE).riskLevel()).isEqualTo(RiskLevel.LOW);
        }

        @Test
        @Transactional
        void returnsFalseForAbsentSignal() {
            // GEO_SCORE is absent on INVOICE route — recordSignalResult should return false
            var invoiceEntity = repository.saveAndFlush(
                    TestEntityFactory.invoice(UUID.randomUUID(), NOW, TIMEOUT));

            boolean applied = invoiceEntity.recordSignalResult(SignalConfig.GEO_SCORE,
                    SignalState.settled(RiskLevel.LOW));

            assertThat(applied).isFalse();
        }

        @Test
        @Transactional
        void updatesFraudCheckAndReturnsTrue() {
            boolean applied = entity.recordSignalResult(SignalConfig.FRAUD_CHECK,
                    SignalState.settled(SignalOutcome.OK));

            assertThat(applied).isTrue();
            assertThat(entity.getSignals().get(SignalConfig.FRAUD_CHECK).outcome()).isEqualTo(SignalOutcome.OK);
        }

        @Test
        @Transactional
        void fraudIdempotentOnDuplicateDelivery() {
            entity.recordSignalResult(SignalConfig.FRAUD_CHECK, SignalState.settled(SignalOutcome.OK));
            boolean second = entity.recordSignalResult(SignalConfig.FRAUD_CHECK, SignalState.settled(SignalOutcome.FAILED));

            assertThat(second).isFalse();
            assertThat(entity.getSignals().get(SignalConfig.FRAUD_CHECK).outcome()).isEqualTo(SignalOutcome.OK);
        }
    }

    @Nested
    class RecordDecision {

        @Test
        @Transactional
        void recordsDecisionResultAndReturnsTrue() {
            var entity = TestEntityFactory.creditCard(UUID.randomUUID(), NOW, TIMEOUT);
            repository.saveAndFlush(entity);
            var decidedAt = NOW.plusSeconds(5);

            boolean applied = entity.recordDecision(DecisionResult.APPROVED, decidedAt);

            assertThat(applied).isTrue();
            assertThat(entity.getDecisionResult()).isEqualTo(DecisionResult.APPROVED);
            assertThat(entity.getDecidedAt()).isEqualTo(decidedAt);
        }

        @Test
        @Transactional
        void idempotentWhenDecisionAlreadyRecorded() {
            var entity = TestEntityFactory.creditCard(UUID.randomUUID(), NOW, TIMEOUT);
            repository.saveAndFlush(entity);
            var decidedAt = NOW.plusSeconds(5);

            entity.recordDecision(DecisionResult.APPROVED, decidedAt);
            boolean second = entity.recordDecision(DecisionResult.REJECTED, decidedAt);

            assertThat(second).isFalse();
            assertThat(entity.getDecisionResult()).isEqualTo(DecisionResult.APPROVED);
        }
    }

    @Nested
    class FindPendingTimeouts {

        @Test
        @Transactional
        void findsPastDeadlineWithPendingSignals() {
            var entity = TestEntityFactory.creditCard(UUID.randomUUID(), NOW, TIMEOUT);
            repository.saveAndFlush(entity);

            var results = repository.findPendingTimeouts(TIMEOUT.plusSeconds(1));

            assertThat(results).hasSize(1);
            assertThat(results.getFirst().getRequestId()).isEqualTo(entity.getRequestId());
        }

        @Test
        @Transactional
        void excludesNotYetExpired() {
            repository.saveAndFlush(
                    TestEntityFactory.creditCard(UUID.randomUUID(), NOW, TIMEOUT));

            var results = repository.findPendingTimeouts(TIMEOUT.minusSeconds(1));

            assertThat(results).isEmpty();
        }

        @Test
        @Transactional
        void excludesFullySettledRequests() {
            var entity = repository.saveAndFlush(
                    TestEntityFactory.creditCard(UUID.randomUUID(), NOW, TIMEOUT));
            entity.recordSignalResult(SignalConfig.GEO_SCORE, SignalState.settled(RiskLevel.LOW));
            entity.recordSignalResult(SignalConfig.FRAUD_CHECK, SignalState.settled(SignalOutcome.OK));
            entity.recordDecision(DecisionResult.APPROVED, NOW.plusSeconds(5));
            repository.flush();

            var results = repository.findPendingTimeouts(TIMEOUT.plusSeconds(1));

            assertThat(results).isEmpty();
        }
    }

    @Nested
    class IsCompleteAfterUpdates {

        @Test
        @Transactional
        void creditCardCompleteAfterBothSignalsSettle() {
            var entity = TestEntityFactory.creditCard(UUID.randomUUID(), NOW, TIMEOUT);
            repository.saveAndFlush(entity);

            entity.recordSignalResult(SignalConfig.GEO_SCORE, SignalState.settled(RiskLevel.LOW));
            entity.recordSignalResult(SignalConfig.FRAUD_CHECK, SignalState.settled(SignalOutcome.OK));

            assertThat(entity.isComplete()).isTrue();
        }

        @Test
        @Transactional
        void creditCardNotCompleteAfterGeoOnly() {
            var entity = TestEntityFactory.creditCard(UUID.randomUUID(), NOW, TIMEOUT);
            repository.saveAndFlush(entity);

            entity.recordSignalResult(SignalConfig.GEO_SCORE, SignalState.settled(RiskLevel.LOW));

            assertThat(entity.isComplete()).isFalse();
        }

        @Test
        @Transactional
        void invoiceCompleteAfterFraudOnly() {
            var entity = TestEntityFactory.invoice(UUID.randomUUID(), NOW, TIMEOUT);
            repository.saveAndFlush(entity);

            entity.recordSignalResult(SignalConfig.FRAUD_CHECK, SignalState.settled(SignalOutcome.OK));

            assertThat(entity.isComplete()).isTrue();
        }
    }
}
