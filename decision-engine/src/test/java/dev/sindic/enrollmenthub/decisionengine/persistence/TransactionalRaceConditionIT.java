package dev.sindic.enrollmenthub.decisionengine.persistence;

import dev.sindic.enrollmenthub.decisionengine.BaseIntegrationTest;
import dev.sindic.enrollmenthub.decisionengine.TestEntityFactory;
import dev.sindic.enrollmenthub.decisionengine.domain.RiskLevel;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalConfig;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalOutcome;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalProcessingState;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Empirically demonstrates the lost-update race that {@code @Transactional}
 * (PostgreSQL READ_COMMITTED isolation) does NOT prevent, and shows that
 * adding {@code SELECT ... FOR UPDATE} closes it.
 *
 * <p>Both tests use {@link TransactionTemplate} (Spring's programmatic
 * equivalent of {@code @Transactional}) and {@link JdbcTemplate} inside each
 * transaction. JPA is deliberately not involved here — this isolates the
 * test from Hibernate dirty-tracking behaviour so we're measuring pure
 * transaction isolation semantics.
 *
 * <p>Two threads each perform a read-modify-write on the same row,
 * coordinated by a {@link CountDownLatch} so both reads complete before
 * either write begins. This is the exact interleaving that ADR-015 and
 * {@code architecture.md §8.7} call out as the scatter-gather hazard.
 *
 * <p>References:
 * <ul>
 *   <li>PostgreSQL 16 docs §13.2.1 "Read Committed Isolation Level" —
 *       SELECT without FOR UPDATE takes no lock; concurrent SELECTs are
 *       allowed; UPDATEs serialize but each writes the value computed
 *       from its earlier stale SELECT.</li>
 *   <li>Spring Framework reference §"Declarative Transaction Management"
 *       — {@code Isolation.DEFAULT} delegates to the database default,
 *       which for PostgreSQL is READ_COMMITTED.</li>
 * </ul>
 */
class TransactionalRaceConditionIT extends BaseIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TIMEOUT_AT = Instant.parse("2026-01-01T00:01:00Z");

    @Autowired EnrollmentRepository repository;
    @Autowired TransactionTemplate txTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JsonMapper jsonMapper;

    @Test
    void transactionalAlone_loses_updates_under_concurrent_readModifyWrite() throws Exception {
        // GIVEN: a fresh enrollment with both signals PENDING.
        UUID enrollmentId = UUID.randomUUID();
        txTemplate.executeWithoutResult(tx -> repository.save(
                TestEntityFactory.creditCard(enrollmentId, CREATED_AT, TIMEOUT_AT)));

        // WHEN: two threads each read, mutate one signal, and write — coordinated
        //       so both reads land before either write. NO SELECT FOR UPDATE.
        runConcurrentReadModifyWrite(enrollmentId, /* useSelectForUpdate */ false,
                SignalConfig.GEO_SCORE, SignalState.settled(RiskLevel.LOW),
                SignalConfig.FRAUD_CHECK, SignalState.settled(SignalOutcome.OK));

        // THEN: only ONE of the two signals is SETTLED. The other was overwritten
        //       by the loser's stale-snapshot UPDATE. This is the lost-update race.
        Map<SignalConfig, SignalState> finalSignals = readSignals(enrollmentId);
        long settledCount = finalSignals.values().stream()
                .filter(s -> s.processingState() == SignalProcessingState.SETTLED)
                .count();

        assertThat(settledCount)
                .as("@Transactional alone (PostgreSQL READ_COMMITTED) does not prevent " +
                    "the lost-update race. Both signals should be SETTLED after both " +
                    "writes; only one is. Actual final state: %s", finalSignals)
                .isEqualTo(1);
    }

    @Test
    void selectForUpdate_preserves_both_updates_under_concurrent_readModifyWrite() throws Exception {
        // GIVEN: a fresh enrollment with both signals PENDING.
        UUID enrollmentId = UUID.randomUUID();
        txTemplate.executeWithoutResult(tx -> repository.save(
                TestEntityFactory.creditCard(enrollmentId, CREATED_AT, TIMEOUT_AT)));

        // WHEN: same race shape, but the SELECT takes a row lock via FOR UPDATE.
        //       The second thread's SELECT blocks until the first commits, so it
        //       reads the post-update state and computes the new map from
        //       fresh data instead of stale data.
        runConcurrentReadModifyWrite(enrollmentId, /* useSelectForUpdate */ true,
                SignalConfig.GEO_SCORE, SignalState.settled(RiskLevel.LOW),
                SignalConfig.FRAUD_CHECK, SignalState.settled(SignalOutcome.OK));

        // THEN: both signals SETTLED — no lost update.
        Map<SignalConfig, SignalState> finalSignals = readSignals(enrollmentId);
        long settledCount = finalSignals.values().stream()
                .filter(s -> s.processingState() == SignalProcessingState.SETTLED)
                .count();

        assertThat(settledCount)
                .as("SELECT ... FOR UPDATE serialises the read-modify-write cycle; " +
                    "both signals should be SETTLED. Actual: %s", finalSignals)
                .isEqualTo(2);
    }

    // -----------------------------------------------------------------------
    // Test infrastructure
    // -----------------------------------------------------------------------

    private void runConcurrentReadModifyWrite(
            UUID enrollmentId,
            boolean useSelectForUpdate,
            SignalConfig signalA, SignalState newStateA,
            SignalConfig signalB, SignalState newStateB) throws Exception {

        // Latch counted down by each thread after its SELECT completes.
        // Both threads await(2) so neither proceeds to the UPDATE until both
        // reads have happened — this is the precise interleaving the lost-update
        // race requires.
        CountDownLatch bothRead = new CountDownLatch(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> readModifyWrite(enrollmentId, useSelectForUpdate,
                    signalA, newStateA, bothRead, failure));
            pool.submit(() -> readModifyWrite(enrollmentId, useSelectForUpdate,
                    signalB, newStateB, bothRead, failure));

            pool.shutdown();
            boolean finished = pool.awaitTermination(20, TimeUnit.SECONDS);
            assertThat(finished).as("worker threads did not complete in time").isTrue();
        } finally {
            if (!pool.isTerminated()) {
                pool.shutdownNow();
            }
        }

        if (failure.get() != null) {
            throw new AssertionError("worker thread failed", failure.get());
        }
    }

    private void readModifyWrite(UUID enrollmentId,
                                 boolean useSelectForUpdate,
                                 SignalConfig signal,
                                 SignalState newState,
                                 CountDownLatch bothRead,
                                 AtomicReference<Throwable> failure) {
        try {
            txTemplate.executeWithoutResult(tx -> {
                // 1. SELECT — with or without row lock.
                String sql = useSelectForUpdate
                        ? "SELECT signals::text FROM enrollment_hub.enrollments " +
                          "WHERE enrollment_id = ? FOR UPDATE"
                        : "SELECT signals::text FROM enrollment_hub.enrollments " +
                          "WHERE enrollment_id = ?";
                String currentJson = jdbcTemplate.queryForObject(sql, String.class, enrollmentId);

                // 2. Coordinate: signal that we've read, wait for the peer to also have read.
                //    Under FOR UPDATE the second SELECT will block at step 1 and never
                //    reach this point until the first transaction commits — that's the
                //    point of the lock.
                bothRead.countDown();
                if (!useSelectForUpdate) {
                    try {
                        boolean signalled = bothRead.await(10, TimeUnit.SECONDS);
                        assertThat(signalled).as("peer thread did not complete its SELECT").isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                }
                // Under FOR UPDATE the second SELECT blocks at step 1 and never
                // reaches this point until the first transaction commits — that
                // is the lock doing its job; no application-level await needed.

                // 3. Compute new signals JSON in application memory based on the
                //    snapshot we read at step 1.
                Map<String, SignalState> updated = parseSignals(currentJson);
                updated.put(signal.name(), newState);
                String newJson = jsonMapper.writeValueAsString(updated);

                // 4. UPDATE — acquires the row-level write lock at this moment.
                //    Without step 1's FOR UPDATE, this lock arrives too late: the
                //    value we're writing was already determined from stale data.
                jdbcTemplate.update(
                        "UPDATE enrollment_hub.enrollments SET signals = ?::jsonb " +
                        "WHERE enrollment_id = ?",
                        newJson, enrollmentId);
            });
        } catch (Throwable ex) {
            failure.compareAndSet(null, ex);
        }
    }

    private Map<String, SignalState> parseSignals(String json) {
        return jsonMapper.readValue(json, new TypeReference<HashMap<String, SignalState>>() {});
    }

    private Map<SignalConfig, SignalState> readSignals(UUID enrollmentId) {
        return txTemplate.execute(tx -> repository.findById(enrollmentId).orElseThrow().getSignals());
    }
}
