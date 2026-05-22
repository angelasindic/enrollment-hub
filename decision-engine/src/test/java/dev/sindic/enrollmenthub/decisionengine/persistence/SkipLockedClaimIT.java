package dev.sindic.enrollmenthub.decisionengine.persistence;

import dev.sindic.enrollmenthub.decisionengine.BaseIntegrationTest;
import dev.sindic.enrollmenthub.decisionengine.TestEntityFactory;
import dev.sindic.enrollmenthub.decisionengine.domain.DecisionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link EnrollmentRepository#claimPendingTimeouts(Instant, java.time.Instant)}'s
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} behaviour against a real PostgreSQL via
 * Testcontainers. Each test asserts one property of the claim contract.
 *
 * <p>Cleanup is by table truncation in {@code @BeforeEach} because the claim query
 * returns <i>every</i> matching row in the database — leftover rows from earlier tests
 * would pollute the expected result sets.
 */
class SkipLockedClaimIT extends BaseIntegrationTest {

    private static final Instant CREATED_AT       = Instant.parse("2025-12-31T23:59:00Z");
    private static final Instant EXPIRED_OLDER    = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant EXPIRED_NEWER    = Instant.parse("2026-01-01T00:00:30Z");
    private static final Instant FUTURE_DEADLINE  = Instant.parse("2099-01-01T00:00:00Z");
    // "Now" used by the claim query — comfortably after both expired deadlines.
    private static final Instant NOW              = Instant.parse("2026-01-01T00:01:00Z");

    @Autowired EnrollmentRepository repository;
    @Autowired TransactionTemplate txTemplate;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void truncate() {
        // Truncate both before (in case a previous test class left rows) and after
        // (this IT commits its inserts via TransactionTemplate — no rollback isolation).
        jdbcTemplate.update("DELETE FROM enrollment_hub.enrollments");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void claimsAllExpiredRowsInTimeoutAscendingOrder_whenNoLocksHeld() {
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        txTemplate.executeWithoutResult(tx -> {
            repository.save(TestEntityFactory.creditCard(older, CREATED_AT, EXPIRED_OLDER));
            repository.save(TestEntityFactory.creditCard(newer, CREATED_AT, EXPIRED_NEWER));
        });

        List<EnrollmentEntity> claimed = txTemplate.execute(tx ->
                repository.claimPendingTimeouts(NOW, PageRequest.ofSize(10)));

        assertThat(claimed)
                .as("ORDER BY timeoutAt ASC — oldest deadline first")
                .extracting(EnrollmentEntity::getRequestId)
                .containsExactly(older, newer);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void respectsPageableBatchSize() {
        UUID first  = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third  = UUID.randomUUID();
        txTemplate.executeWithoutResult(tx -> {
            repository.save(TestEntityFactory.creditCard(first,  CREATED_AT, EXPIRED_OLDER));
            repository.save(TestEntityFactory.creditCard(second, CREATED_AT, EXPIRED_OLDER.plusSeconds(1)));
            repository.save(TestEntityFactory.creditCard(third,  CREATED_AT, EXPIRED_OLDER.plusSeconds(2)));
        });

        List<EnrollmentEntity> firstBatch = txTemplate.execute(tx ->
                repository.claimPendingTimeouts(NOW, PageRequest.ofSize(2)));

        assertThat(firstBatch)
                .as("PageRequest.ofSize(2) → exactly two rows, ordered by timeoutAt ASC")
                .extracting(EnrollmentEntity::getRequestId)
                .containsExactly(first, second);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void excludesRowsThatAlreadyHaveADecision() {
        UUID pending = UUID.randomUUID();
        UUID decided = UUID.randomUUID();
        txTemplate.executeWithoutResult(tx -> {
            repository.save(TestEntityFactory.creditCard(pending, CREATED_AT, EXPIRED_OLDER));
            repository.save(TestEntityFactory.creditCard(decided, CREATED_AT, EXPIRED_OLDER));
            // Mark the second row as decided via the production write path (ADR-015 §Write path).
            repository.recordDecision(decided, DecisionResult.APPROVED, UUID.randomUUID(), NOW.minusSeconds(5));
        });

        List<EnrollmentEntity> claimed = txTemplate.execute(tx ->
                repository.claimPendingTimeouts(NOW, PageRequest.ofSize(10)));

        assertThat(claimed)
                .as("WHERE decisionResult IS NULL — decided rows must not be claimed")
                .extracting(EnrollmentEntity::getRequestId)
                .containsExactly(pending)
                .doesNotContain(decided);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void excludesRowsWhoseDeadlineHasNotPassed() {
        UUID expired  = UUID.randomUUID();
        UUID upcoming = UUID.randomUUID();
        txTemplate.executeWithoutResult(tx -> {
            repository.save(TestEntityFactory.creditCard(expired,  CREATED_AT, EXPIRED_OLDER));
            repository.save(TestEntityFactory.creditCard(upcoming, CREATED_AT, FUTURE_DEADLINE));
        });

        List<EnrollmentEntity> claimed = txTemplate.execute(tx ->
                repository.claimPendingTimeouts(NOW, PageRequest.ofSize(10)));

        assertThat(claimed)
                .as("WHERE timeoutAt <= now — rows with future deadlines must not be claimed")
                .extracting(EnrollmentEntity::getRequestId)
                .containsExactly(expired);
    }

    /**
     * The key SKIP LOCKED property: a row locked by another transaction is skipped
     * silently — the claim query does NOT block waiting for the lock to release.
     *
     * <p>If the JPA hint is ever lost or misconfigured (or a future Hibernate
     * version changes the {@code -2} sentinel), the claim call below will block on
     * T1's lock and the {@code @Timeout} on the test method will fire — turning a
     * silent regression into a loud, fast failure.
     */
    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void skipsRowsLockedByAnotherTransaction() throws InterruptedException {
        UUID locked   = UUID.randomUUID();
        UUID claimable = UUID.randomUUID();
        txTemplate.executeWithoutResult(tx -> {
            repository.save(TestEntityFactory.creditCard(locked,    CREATED_AT, EXPIRED_OLDER));
            repository.save(TestEntityFactory.creditCard(claimable, CREATED_AT, EXPIRED_NEWER));
        });

        var t1HoldsLock = new CountDownLatch(1);
        var t2HasClaimed = new CountDownLatch(1);
        var t1Failure = new AtomicReference<Throwable>();

        // T1: take PESSIMISTIC_WRITE on `locked` and hold it until T2 has claimed.
        Thread t1 = new Thread(() -> {
            try {
                txTemplate.executeWithoutResult(tx -> {
                    repository.findByRequestIdForUpdate(locked)
                            .orElseThrow(() -> new IllegalStateException("locked row not found"));
                    t1HoldsLock.countDown();
                    try {
                        if (!t2HasClaimed.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("T2 did not finish in time");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                });
            } catch (Throwable ex) {
                t1Failure.compareAndSet(null, ex);
            }
        }, "skip-locked-T1");
        t1.start();

        assertThat(t1HoldsLock.await(5, TimeUnit.SECONDS))
                .as("T1 must acquire the lock before T2 attempts the claim")
                .isTrue();

        // T2: claim. With SKIP LOCKED this must return immediately with only
        // `claimable` — it must not block on T1's lock on `locked`.
        List<EnrollmentEntity> claimed;
        try {
            claimed = txTemplate.execute(tx ->
                    repository.claimPendingTimeouts(NOW, PageRequest.ofSize(10)));
        } finally {
            t2HasClaimed.countDown();
            t1.join(5_000);
        }

        assertThat(claimed)
                .as("SKIP LOCKED — locked row is skipped; only the unlocked one is claimed")
                .extracting(EnrollmentEntity::getRequestId)
                .containsExactly(claimable);

        if (t1Failure.get() != null) {
            throw new AssertionError("T1 thread failed", t1Failure.get());
        }
    }
}
