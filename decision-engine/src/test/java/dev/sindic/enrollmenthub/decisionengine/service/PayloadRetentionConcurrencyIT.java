package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.decisionengine.BaseIntegrationTest;
import dev.sindic.enrollmenthub.decisionengine.TestEntityFactory;
import dev.sindic.enrollmenthub.decisionengine.domain.CheckOutcome;
import dev.sindic.enrollmenthub.decisionengine.domain.RiskLevel;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalConfig;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalState;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.sindic.enrollmenthub.decisionengine.amqp.AmqpConfig.DECISION_EXCHANGE;
import static dev.sindic.enrollmenthub.decisionengine.amqp.AmqpConfig.DECISION_ROUTING_KEY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that {@link PayloadRetentionJob} and the ADR-17 dispatch path are safe to run at the same
 * time — which they do: {@code spring.threads.virtual.enabled} replaces the single-threaded
 * {@code ThreadPoolTaskScheduler} with a {@code SimpleAsyncTaskScheduler} that has no concurrency
 * limit, so the two {@code @Scheduled} passes are not serialised onto one thread.
 *
 * <p>The safety argument is predicate disjointness on {@code dispatched_at}: the stamp is written
 * only after the publisher confirm, every publisher reads the payload from an entity loaded before
 * that stamp, and retention claims only stamped rows. These tests pin the two places that argument
 * could fail in practice — a claim that blocks instead of skipping, and an erasure that lands on a
 * row still awaiting delivery.
 */
@TestPropertySource(properties = {
        "decision-engine.retention.interval=PT1H",
        "decision-engine.sweep.interval=PT1H"
})
class PayloadRetentionConcurrencyIT extends BaseIntegrationTest {

    private static final int OUTBOX_ROWS = 25;

    @Autowired PayloadRetentionJob retentionJob;
    @Autowired DecisionDispatcher dispatcher;
    @Autowired EnrollmentRepository repository;
    @Autowired TransactionTemplate txTemplate;
    @Autowired AmqpAdmin amqpAdmin;
    @Autowired JsonMapper jsonMapper;

    private final List<String> declaredQueues = new ArrayList<>();

    @AfterEach
    void cleanUpCaptureQueues() {
        declaredQueues.forEach(amqpAdmin::deleteQueue);
        declaredQueues.clear();
    }

    @Test
    void retentionSkipsARowAnotherTransactionHolds_ratherThanWaitingOnIt() throws Exception {
        // A late result for an already-delivered enrollment locks the row with WAIT semantics.
        // Retention must step around it: its claim is FOR UPDATE SKIP LOCKED, so a handler holding
        // a row can never stall the retention pass, and the pass can never stall the handler.
        var enrollmentId = UUID.randomUUID();
        seedDispatched(enrollmentId, UUID.randomUUID());

        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        try {
            holder.submit(() -> txTemplate.executeWithoutResult(status -> {
                repository.findByEnrollmentIdForUpdate(enrollmentId).orElseThrow();
                locked.countDown();
                awaitQuietly(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            // Would deadlock here if the claim waited on the held row instead of skipping it.
            retentionJob.stripDispatchedPayloads();

            assertThat(repository.findById(enrollmentId).orElseThrow().getOriginalRequest())
                    .as("a row held by another transaction is skipped, not stripped")
                    .isNotNull();
        } finally {
            release.countDown();
            holder.shutdown();
            assertThat(holder.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        // Once the lock is gone the same row is ordinary work again.
        retentionJob.stripDispatchedPayloads();
        assertThat(repository.findById(enrollmentId).orElseThrow().getOriginalRequest()).isNull();
    }

    @Test
    void retentionRunningDuringDispatch_neverStripsARowAwaitingDelivery() throws Exception {
        // The hazard worth pinning: a payload erased while its decision is still in the outbox
        // would leave a row the relay can re-claim but not rebuild an event from. Retention runs in
        // a tight loop for the whole of a dispatch batch, so it observes rows on both sides of the
        // dispatched_at transition.
        bindCaptureQueue();
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < OUTBOX_ROWS; i++) {
            var id = UUID.randomUUID();
            seedDecided(id, UUID.randomUUID());
            ids.add(id);
        }

        var stop = new AtomicBoolean(false);
        ExecutorService retention = Executors.newSingleThreadExecutor();
        try {
            retention.submit(() -> {
                while (!stop.get()) {
                    retentionJob.stripDispatchedPayloads();
                }
            });
            dispatcher.dispatchPending(OUTBOX_ROWS);
        } finally {
            stop.set(true);
            retention.shutdown();
            assertThat(retention.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }

        for (var id : ids) {
            var row = repository.findById(id).orElseThrow();
            assertThat(row.getDispatchedAt())
                    .as("concurrent retention must not prevent delivery of %s", id)
                    .isNotNull();
            if (row.getOriginalRequest() == null) {
                assertThat(row.getDispatchedAt())
                        .as("payload erased on %s while it was still awaiting delivery", id)
                        .isNotNull();
            }
        }
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private void seedDecided(UUID enrollmentId, UUID decisionId) {
        txTemplate.executeWithoutResult(status -> {
            repository.saveAndFlush(TestEntityFactory.creditCard(
                    enrollmentId, Instant.now(), Instant.now().plusSeconds(300)));
            var settled = new EnumMap<SignalConfig, SignalState>(SignalConfig.class);
            settled.put(SignalConfig.GEO_SCORE, new SignalState.Scored(RiskLevel.LOW));
            settled.put(SignalConfig.FRAUD_CHECK, new SignalState.Checked(CheckOutcome.OK));
            repository.completeWithDecision(enrollmentId,
                    SignalMapJson.write(jsonMapper, settled), "APPROVED", decisionId, Instant.now());
        });
    }

    private void seedDispatched(UUID enrollmentId, UUID decisionId) {
        seedDecided(enrollmentId, decisionId);
        txTemplate.executeWithoutResult(status ->
                assertThat(repository.markDispatched(enrollmentId, Instant.now())).isEqualTo(1));
    }

    /** Publishing is mandatory, so the decisions exchange needs a bound queue or the publish throws. */
    private void bindCaptureQueue() {
        var name = "test.retention.capture." + UUID.randomUUID();
        amqpAdmin.declareQueue(new Queue(name, false, false, false));
        amqpAdmin.declareBinding(new Binding(name, Binding.DestinationType.QUEUE,
                DECISION_EXCHANGE, DECISION_ROUTING_KEY, null));
        declaredQueues.add(name);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
