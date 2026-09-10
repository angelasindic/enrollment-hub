package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.decisionengine.BaseIntegrationTest;
import dev.sindic.enrollmenthub.decisionengine.TestEntityFactory;
import dev.sindic.enrollmenthub.decisionengine.domain.CheckOutcome;
import dev.sindic.enrollmenthub.decisionengine.domain.DecisionResult;
import dev.sindic.enrollmenthub.decisionengine.domain.RiskLevel;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalConfig;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalState;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link PayloadRetentionJob} (ADR-20) against Testcontainers Postgres.
 *
 * <p>The job is driven directly rather than through its {@code @Scheduled} tick, and both
 * background schedules are pushed an hour out: the retention tick so it cannot race the
 * assertions, and the sweep so its timeout phase cannot finalize — and thereby dispatch — the
 * deliberately ancient undispatched row these tests depend on.
 *
 * <p>There is no waiting period to exercise (ADR-20 §Why there is no waiting period), so the cases
 * that matter are the states the strip must not reach: undispatched at any age, and the outbox
 * state. Ages are seeded on {@code dispatched_at} and {@code created_at} directly rather than by
 * moving a mock clock.
 */
@TestPropertySource(properties = {
        "decision-engine.retention.interval=PT1H",
        "decision-engine.sweep.interval=PT1H"
})
class PayloadRetentionIT extends BaseIntegrationTest {

    @Autowired PayloadRetentionJob retentionJob;
    @Autowired EnrollmentRepository repository;
    @Autowired TransactionTemplate txTemplate;
    @Autowired JsonMapper jsonMapper;

    @Test
    void dispatched_erasesThePayload_andKeepsTheDecisionRecord() {
        var enrollmentId = UUID.randomUUID();
        var decisionId = UUID.randomUUID();
        seedDispatched(enrollmentId, decisionId, Instant.now());

        retentionJob.stripDispatchedPayloads();

        var row = repository.findById(enrollmentId).orElseThrow();
        assertThat(row.getOriginalRequest()).isNull();
        // Everything the decision of record is made of outlives the payload.
        assertThat(row.getDecisionResult()).isEqualTo(DecisionResult.APPROVED);
        assertThat(row.getDecisionId()).isEqualTo(decisionId);
        assertThat(row.getSignals()).containsKeys(SignalConfig.GEO_SCORE, SignalConfig.FRAUD_CHECK);
        assertThat(row.getDecidedAt()).isNotNull();
        assertThat(row.getDispatchedAt()).isNotNull();
        assertThat(row.getCreatedAt()).isNotNull();
    }

    @Test
    void strippedRow_stillResolvesDecisionIdToEnrollmentId() {
        // The linkage exists nowhere else: ADR-17 withholds enrollmentId from the decision event,
        // so the Account Service knows only the decisionId. Erasing the payload must not break the
        // bridge back to this service's traces and logs.
        var enrollmentId = UUID.randomUUID();
        var decisionId = UUID.randomUUID();
        seedDispatched(enrollmentId, decisionId, Instant.now());

        retentionJob.stripDispatchedPayloads();

        var row = repository.findById(enrollmentId).orElseThrow();
        assertThat(row.getOriginalRequest()).isNull();
        assertThat(row.getDecisionId()).isEqualTo(decisionId);
        assertThat(row.getEnrollmentId()).isEqualTo(enrollmentId);
    }

    @Test
    void undispatchedRow_keepsItsPayload_regardlessOfAge() {
        // The payload is the outbox's only source for the decision event, so age is irrelevant
        // while dispatched_at is NULL — this row is a month old and still untouchable.
        var enrollmentId = UUID.randomUUID();
        var ancient = Instant.now().minus(Duration.ofDays(30));
        txTemplate.executeWithoutResult(status -> repository.saveAndFlush(
                TestEntityFactory.creditCard(enrollmentId, ancient, ancient.plusSeconds(300))));

        retentionJob.stripDispatchedPayloads();

        assertThat(repository.findById(enrollmentId).orElseThrow().getOriginalRequest()).isNotNull();
    }

    @Test
    void decidedButUndispatchedRow_keepsItsPayload() {
        // The outbox state itself: decided, publish not yet confirmed. The dispatch relay will
        // re-claim this row and rebuild the event from the payload, so it must survive.
        var enrollmentId = UUID.randomUUID();
        seedDecided(enrollmentId, UUID.randomUUID());

        retentionJob.stripDispatchedPayloads();

        assertThat(repository.findById(enrollmentId).orElseThrow().getOriginalRequest()).isNotNull();
    }

    @Test
    void erasureIsOutcomeAgnostic() {
        // dispatched_at is the whole condition — no outcome is kept longer or shorter than
        // another, so a rejection is erased on the same pass as an approval.
        var rejected = UUID.randomUUID();
        seedDispatched(rejected, UUID.randomUUID(), Instant.now(), "REJECTED");

        retentionJob.stripDispatchedPayloads();

        var row = repository.findById(rejected).orElseThrow();
        assertThat(row.getOriginalRequest()).isNull();
        assertThat(row.getDecisionResult()).isEqualTo(DecisionResult.REJECTED);
    }

    @Test
    void undispatchedRejection_keepsItsPayload() {
        // The guard holds for every outcome: until the confirm returns, the payload is still the
        // outbox's only source for the event — rejections included.
        var enrollmentId = UUID.randomUUID();
        seedDecided(enrollmentId, UUID.randomUUID(), "REJECTED");

        retentionJob.stripDispatchedPayloads();

        assertThat(repository.findById(enrollmentId).orElseThrow().getOriginalRequest()).isNotNull();
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    /** A row in the outbox state — decided, publish not yet confirmed. */
    private void seedDecided(UUID enrollmentId, UUID decisionId) {
        seedDecided(enrollmentId, decisionId, "APPROVED");
    }

    private void seedDecided(UUID enrollmentId, UUID decisionId, String decision) {
        txTemplate.executeWithoutResult(status -> {
            repository.saveAndFlush(TestEntityFactory.creditCard(
                    enrollmentId, Instant.now(), Instant.now().plusSeconds(300)));
            var settled = new EnumMap<SignalConfig, SignalState>(SignalConfig.class);
            settled.put(SignalConfig.GEO_SCORE, new SignalState.Scored(RiskLevel.LOW));
            settled.put(SignalConfig.FRAUD_CHECK, new SignalState.Checked(CheckOutcome.OK));
            repository.completeWithDecision(enrollmentId,
                    SignalMapJson.write(jsonMapper, settled), decision, decisionId, Instant.now());
        });
    }

    /** A delivered row, stamped at {@code dispatchedAt} — the only state retention may touch. */
    private void seedDispatched(UUID enrollmentId, UUID decisionId, Instant dispatchedAt) {
        seedDispatched(enrollmentId, decisionId, dispatchedAt, "APPROVED");
    }

    private void seedDispatched(UUID enrollmentId, UUID decisionId, Instant dispatchedAt, String decision) {
        seedDecided(enrollmentId, decisionId, decision);
        txTemplate.executeWithoutResult(status ->
                assertThat(repository.markDispatched(enrollmentId, dispatchedAt)).isEqualTo(1));
    }
}
