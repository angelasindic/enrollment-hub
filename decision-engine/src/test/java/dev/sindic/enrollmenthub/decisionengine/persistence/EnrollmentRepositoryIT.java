package dev.sindic.enrollmenthub.decisionengine.persistence;

import dev.sindic.enrollmenthub.decisionengine.BaseIntegrationTest;
import dev.sindic.enrollmenthub.decisionengine.domain.*;
import dev.sindic.enrollmenthub.decisionengine.TestEntityFactory;
import org.junit.jupiter.api.BeforeEach;
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
            var enrollmentId = UUID.randomUUID();
            var entity = TestEntityFactory.creditCard(enrollmentId, NOW, TIMEOUT);

            repository.saveAndFlush(entity);
            var loaded = repository.findById(enrollmentId).orElseThrow();

            assertThat(loaded.getEnrollmentId()).isEqualTo(enrollmentId);
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
            var enrollmentId = UUID.randomUUID();
            var entity = TestEntityFactory.invoice(enrollmentId, NOW, TIMEOUT);

            repository.saveAndFlush(entity);
            var loaded = repository.findById(enrollmentId).orElseThrow();

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
            var enrollmentId = UUID.randomUUID();
            repository.saveAndFlush(TestEntityFactory.creditCard(enrollmentId, NOW, TIMEOUT));

            var locked = repository.findByEnrollmentIdForUpdate(enrollmentId);

            assertThat(locked).isPresent();
            assertThat(locked.get().getEnrollmentId()).isEqualTo(enrollmentId);
        }

        @Test
        @Transactional
        void returnsEmptyForNonexistentRequest() {
            assertThat(repository.findByEnrollmentIdForUpdate(UUID.randomUUID())).isEmpty();
        }
    }

    /** Repository-level write path for the signals JSONB column (ADR-015 §Write path). */
    @Nested
    class UpdateSignals {

        @Test
        @Transactional
        void writesJsonbColumn_andReturnsOneRow() {
            var enrollmentId = UUID.randomUUID();
            repository.saveAndFlush(TestEntityFactory.creditCard(enrollmentId, NOW, TIMEOUT));

            // Build a new signal map: GEO_SCORE settled HIGH; FRAUD_CHECK still PENDING.
            var newSignals = new EnumMap<>(SignalConfig.initializeFor(PaymentType.CREDIT_CARD));
            newSignals.put(SignalConfig.GEO_SCORE, SignalState.settled(RiskLevel.HIGH));
            var json = jsonMapper.writeValueAsString(newSignals);

            int rows = repository.updateSignals(enrollmentId, json);

            assertThat(rows).isEqualTo(1);
            assertSignalsRoundTrip(enrollmentId, newSignals);
        }

        @Test
        @Transactional
        void returnsZero_whenEnrollmentIdDoesNotExist() {
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
            var enrollmentId = UUID.randomUUID();
            repository.saveAndFlush(TestEntityFactory.creditCard(enrollmentId, NOW, TIMEOUT));

            var firstWrite = new EnumMap<>(SignalConfig.initializeFor(PaymentType.CREDIT_CARD));
            firstWrite.put(SignalConfig.GEO_SCORE, SignalState.settled(RiskLevel.LOW));
            repository.updateSignals(enrollmentId, jsonMapper.writeValueAsString(firstWrite));

            var secondWrite = new EnumMap<>(SignalConfig.initializeFor(PaymentType.CREDIT_CARD));
            secondWrite.put(SignalConfig.FRAUD_CHECK, SignalState.settled(SignalOutcome.OK));
            repository.updateSignals(enrollmentId, jsonMapper.writeValueAsString(secondWrite));

            assertSignalsRoundTrip(enrollmentId, secondWrite);
        }
    }

    /** Repository-level write path for the combined signals+decision UPDATE (ADR-015 §Write path). */
    @Nested
    class CompleteWithDecisionMethod {

        private static final String SETTLED_SIGNALS_JSON = """
                {"FRAUD_CHECK":{"processingState":"SETTLED","outcome":"OK"},
                 "GEO_SCORE":{"processingState":"SETTLED","riskLevel":"LOW"}}
                """;

        @Test
        @Transactional
        void persistsAllDecisionFields_andReturnsOneRow() {
            var enrollmentId = UUID.randomUUID();
            repository.saveAndFlush(TestEntityFactory.creditCard(enrollmentId, NOW, TIMEOUT));
            var decisionId = UUID.randomUUID();
            var decidedAt = NOW.plusSeconds(5);

            int rows = repository.completeWithDecision(
                    enrollmentId, SETTLED_SIGNALS_JSON, DecisionResult.APPROVED.name(), decisionId, decidedAt);

            assertThat(rows).isEqualTo(1);

            // Fresh read via JdbcTemplate — the L1 cache has a stale loaded copy.
            // JDBC maps TIMESTAMPTZ to java.sql.Timestamp; convert to Instant for comparison.
            var row = jdbcTemplate.queryForMap(
                    "SELECT decision_result, decision_id, decided_at, signals::text AS signals " +
                            "FROM enrollment_hub.enrollments WHERE enrollment_id = ?",
                    enrollmentId);
            assertThat(row.get("decision_result")).isEqualTo("APPROVED");
            assertThat(row.get("decision_id")).isEqualTo(decisionId);
            assertThat(((java.sql.Timestamp) row.get("decided_at")).toInstant()).isEqualTo(decidedAt);
            assertThat((String) row.get("signals"))
                    .contains("\"GEO_SCORE\"").contains("\"LOW\"")
                    .contains("\"FRAUD_CHECK\"").contains("\"OK\"");
        }

        @Test
        @Transactional
        void returnsZero_whenDecisionAlreadyRecorded_andDoesNotOverwrite() {
            var enrollmentId = UUID.randomUUID();
            repository.saveAndFlush(TestEntityFactory.creditCard(enrollmentId, NOW, TIMEOUT));
            var firstDecisionId = UUID.randomUUID();
            repository.completeWithDecision(enrollmentId, SETTLED_SIGNALS_JSON,
                    DecisionResult.APPROVED.name(), firstDecisionId, NOW.plusSeconds(5));

            int secondRows = repository.completeWithDecision(enrollmentId, SETTLED_SIGNALS_JSON,
                    DecisionResult.REJECTED.name(), UUID.randomUUID(), NOW.plusSeconds(10));

            assertThat(secondRows)
                    .as("guard: decision_result IS NULL prevents a second write")
                    .isZero();

            // Confirm the first decision survived intact.
            var row = jdbcTemplate.queryForMap(
                    "SELECT decision_result, decision_id FROM enrollment_hub.enrollments WHERE enrollment_id = ?",
                    enrollmentId);
            assertThat(row.get("decision_result")).isEqualTo("APPROVED");
            assertThat(row.get("decision_id")).isEqualTo(firstDecisionId);
        }

        @Test
        @Transactional
        void returnsZero_whenEnrollmentIdDoesNotExist() {
            int rows = repository.completeWithDecision(
                    UUID.randomUUID(), SETTLED_SIGNALS_JSON,
                    DecisionResult.APPROVED.name(), UUID.randomUUID(), NOW);

            assertThat(rows).isZero();
        }
    }

    @Nested
    class FindPendingTimeouts {

        // findPendingTimeouts() is a table-wide query, so committed rows left by the async IT
        // classes that share this Postgres container would pollute these assertions. Start each
        // test from a clean table; the delete is rolled back with the test's transaction.
        @BeforeEach
        void cleanTable() {
            repository.deleteAll();
        }

        @Test
        @Transactional
        void findsPastDeadlineWithPendingSignals() {
            var entity = TestEntityFactory.creditCard(UUID.randomUUID(), NOW, TIMEOUT);
            repository.saveAndFlush(entity);

            var results = repository.findPendingTimeouts(TIMEOUT.plusSeconds(1));

            assertThat(results).hasSize(1);
            assertThat(results.getFirst().getEnrollmentId()).isEqualTo(entity.getEnrollmentId());
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
            var enrollmentId = UUID.randomUUID();
            repository.saveAndFlush(TestEntityFactory.creditCard(enrollmentId, NOW, TIMEOUT));
            repository.completeWithDecision(enrollmentId, "{}",
                    DecisionResult.APPROVED.name(), UUID.randomUUID(), NOW.plusSeconds(5));

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

    private void assertSignalsRoundTrip(UUID enrollmentId, Map<SignalConfig, SignalState> expected) {
        var json = jdbcTemplate.queryForObject(
                "SELECT signals::text FROM enrollment_hub.enrollments WHERE enrollment_id = ?",
                String.class, enrollmentId);
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
