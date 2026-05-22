package dev.sindic.enrollmenthub.decisionengine.persistence;

import dev.sindic.enrollmenthub.decisionengine.BaseIntegrationTest;
import dev.sindic.enrollmenthub.decisionengine.domain.*;
import dev.sindic.enrollmenthub.decisionengine.TestEntityFactory;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EnrollmentRepositoryIT extends BaseIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-04-09T12:00:00Z");
    private static final Instant TIMEOUT = NOW.plusSeconds(60);

    @Autowired private EnrollmentRepository repository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JsonMapper jsonMapper;

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

    /** Repository-level write path for the signals JSONB column (ADR-015 §Write path). */
    @Nested
    class UpdateSignals {

        @Test
        @Transactional
        void writesJsonbColumn_andReturnsOneRow() {
            var requestId = UUID.randomUUID();
            repository.saveAndFlush(TestEntityFactory.creditCard(requestId, NOW, TIMEOUT));

            // Build a new signal map: GEO_SCORE settled HIGH; FRAUD_CHECK still PENDING.
            var newSignals = new EnumMap<>(SignalConfig.initializeFor(PaymentType.CREDIT_CARD));
            newSignals.put(SignalConfig.GEO_SCORE, SignalState.settled(RiskLevel.HIGH));
            var json = jsonMapper.writeValueAsString(newSignals);

            int rows = repository.updateSignals(requestId, json);

            assertThat(rows).isEqualTo(1);
            assertSignalsRoundTrip(requestId, newSignals);
        }

        @Test
        @Transactional
        void returnsZero_whenRequestIdDoesNotExist() {
            var json = jsonMapper.writeValueAsString(
                    SignalConfig.initializeFor(PaymentType.CREDIT_CARD));

            int rows = repository.updateSignals(UUID.randomUUID(), json);

            assertThat(rows)
                    .as("no row → row count is 0; caller asserts on this and throws")
                    .isZero();
        }

        @Test
        @Transactional
        void overwritesPreviousJsonbValue_completely() {
            // Set initial state to one shape, then UPDATE to a different shape;
            // verify the second shape replaces the first wholesale (not merged).
            var requestId = UUID.randomUUID();
            repository.saveAndFlush(TestEntityFactory.creditCard(requestId, NOW, TIMEOUT));

            var firstWrite = new EnumMap<>(SignalConfig.initializeFor(PaymentType.CREDIT_CARD));
            firstWrite.put(SignalConfig.GEO_SCORE, SignalState.settled(RiskLevel.LOW));
            repository.updateSignals(requestId, jsonMapper.writeValueAsString(firstWrite));

            var secondWrite = new EnumMap<>(SignalConfig.initializeFor(PaymentType.CREDIT_CARD));
            secondWrite.put(SignalConfig.FRAUD_CHECK, SignalState.settled(SignalOutcome.OK));
            repository.updateSignals(requestId, jsonMapper.writeValueAsString(secondWrite));

            assertSignalsRoundTrip(requestId, secondWrite);
        }
    }

    /** Repository-level write path for the scalar decision columns (ADR-015 §Write path symmetry). */
    @Nested
    class RecordDecisionMethod {

        @Test
        @Transactional
        void persistsAllDecisionFields_andReturnsOneRow() {
            var requestId = UUID.randomUUID();
            repository.saveAndFlush(TestEntityFactory.creditCard(requestId, NOW, TIMEOUT));
            var decisionId = UUID.randomUUID();
            var decidedAt = NOW.plusSeconds(5);

            int rows = repository.recordDecision(
                    requestId, DecisionResult.APPROVED, decisionId, decidedAt);

            assertThat(rows).isEqualTo(1);

            // Fresh read via JdbcTemplate — the L1 cache has a stale loaded copy.
            // JDBC maps TIMESTAMPTZ to java.sql.Timestamp; convert to Instant for comparison.
            var row = jdbcTemplate.queryForMap(
                    "SELECT decision_result, decision_id, decided_at " +
                            "FROM enrollment_hub.enrollments WHERE request_id = ?",
                    requestId);
            assertThat(row.get("decision_result")).isEqualTo("APPROVED");
            assertThat(row.get("decision_id")).isEqualTo(decisionId);
            assertThat(((java.sql.Timestamp) row.get("decided_at")).toInstant()).isEqualTo(decidedAt);
        }

        @Test
        @Transactional
        void returnsZero_whenDecisionAlreadyRecorded_andDoesNotOverwrite() {
            var requestId = UUID.randomUUID();
            repository.saveAndFlush(TestEntityFactory.creditCard(requestId, NOW, TIMEOUT));
            var firstDecisionId = UUID.randomUUID();
            repository.recordDecision(requestId, DecisionResult.APPROVED, firstDecisionId, NOW.plusSeconds(5));

            int secondRows = repository.recordDecision(
                    requestId, DecisionResult.REJECTED, UUID.randomUUID(), NOW.plusSeconds(10));

            assertThat(secondRows)
                    .as("guard: decisionResult IS NULL prevents a second write")
                    .isZero();

            // Confirm the first decision survived intact.
            var row = jdbcTemplate.queryForMap(
                    "SELECT decision_result, decision_id FROM enrollment_hub.enrollments WHERE request_id = ?",
                    requestId);
            assertThat(row.get("decision_result")).isEqualTo("APPROVED");
            assertThat(row.get("decision_id")).isEqualTo(firstDecisionId);
        }

        @Test
        @Transactional
        void returnsZero_whenRequestIdDoesNotExist() {
            int rows = repository.recordDecision(
                    UUID.randomUUID(), DecisionResult.APPROVED, UUID.randomUUID(), NOW);

            assertThat(rows).isZero();
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
            var requestId = UUID.randomUUID();
            repository.saveAndFlush(TestEntityFactory.creditCard(requestId, NOW, TIMEOUT));
            repository.recordDecision(requestId, DecisionResult.APPROVED, UUID.randomUUID(), NOW.plusSeconds(5));

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
            // Direct map mutation here is test-only state-setup (analogous to a SQL
            // INSERT in fixtures). Production code path writes via repository.updateSignals.
            entity.getSignals().put(SignalConfig.GEO_SCORE, SignalState.settled(RiskLevel.LOW));
            entity.getSignals().put(SignalConfig.FRAUD_CHECK, SignalState.settled(SignalOutcome.OK));

            assertThat(entity.isComplete()).isTrue();
        }

        @Test
        @Transactional
        void creditCardNotCompleteAfterGeoOnly() {
            var entity = TestEntityFactory.creditCard(UUID.randomUUID(), NOW, TIMEOUT);
            entity.getSignals().put(SignalConfig.GEO_SCORE, SignalState.settled(RiskLevel.LOW));

            assertThat(entity.isComplete()).isFalse();
        }

        @Test
        @Transactional
        void invoiceCompleteAfterFraudOnly() {
            var entity = TestEntityFactory.invoice(UUID.randomUUID(), NOW, TIMEOUT);
            entity.getSignals().put(SignalConfig.FRAUD_CHECK, SignalState.settled(SignalOutcome.OK));

            assertThat(entity.isComplete()).isTrue();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void assertSignalsRoundTrip(UUID requestId, Map<SignalConfig, SignalState> expected) {
        var json = jdbcTemplate.queryForObject(
                "SELECT signals::text FROM enrollment_hub.enrollments WHERE request_id = ?",
                String.class, requestId);
        assertThat(json).isNotNull();
        Map<String, SignalState> actual =
                jsonMapper.readValue(json, new TypeReference<HashMap<String, SignalState>>() {});
        var expectedAsStringKeys = new HashMap<String, SignalState>();
        expected.forEach((k, v) -> expectedAsStringKeys.put(k.name(), v));
        assertThat(actual)
                .as("the JSONB column contains exactly the map we wrote — no merge, no drift")
                .containsExactlyInAnyOrderEntriesOf(expectedAsStringKeys);
    }
}
