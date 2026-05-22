package dev.sindic.enrollmenthub.decisionengine.persistence;

import dev.sindic.enrollmenthub.decisionengine.domain.DecisionResult;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the {@code enrollment_hub.enrollments} correlation table.
 *
 * <h2>Two locking patterns coexist here</h2>
 *
 * <p><b>Specific-row coordination</b> (ADR-015 §"Concurrent Scatter-Gather Completion").
 * Result handlers serialise on a known {@code requestId} via
 * {@link #findByRequestIdForUpdate(UUID)} — {@code PESSIMISTIC_WRITE} without
 * {@code SKIP LOCKED}. If another transaction holds the row, the second handler
 * <i>waits</i> for the first to commit so it observes the post-first-commit state.
 * This is correct because the two handlers are processing different signals that
 * land on the same row; serialising is the desired outcome.
 *
 * <p><b>Job-queue claim</b> (ADR-010, planned timeout poller). The poller scans
 * the table for expired-and-undecided rows and claims a batch to process. It uses
 * {@link #claimPendingTimeouts(Instant, Pageable)} — {@code PESSIMISTIC_WRITE}
 * with {@code SKIP LOCKED}. Rows currently locked by a result handler are
 * <i>skipped</i>, not waited on. The poller has no business serialising against
 * a handler that's already producing the live result; that handler will settle
 * the signal through its own path. The poller's job is the inverse subset: rows
 * that no handler is currently touching.
 *
 * <p>See {@link #findPendingTimeouts(Instant)} for the read-only, lock-free
 * variant intended for diagnostics and tests, not for claim-and-process work.
 */
public interface EnrollmentRepository extends JpaRepository<EnrollmentEntity, UUID> {

    /**
     * Loads the correlation record with a {@code PESSIMISTIC_WRITE} lock.
     * The row lock is held until the enclosing transaction commits, preventing
     * concurrent handlers from reading or writing the same row.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM EnrollmentEntity r WHERE r.requestId = :requestId")
    Optional<EnrollmentEntity> findByRequestIdForUpdate(@Param("requestId") UUID requestId);

    /**
     * Replaces the {@code signals} JSONB column with the given serialised value.
     * Per ADR-015 §Write path, the JSON-mapped collection column is written via an explicit
     * SQL {@code UPDATE} rather than via JPA dirty-tracking; the returned row
     * count is the persistence guarantee.
     *
     * <p>Native query because PostgreSQL needs an explicit {@code ::jsonb} (or
     * {@code CAST(… AS jsonb)}) on the bound text parameter; the JPQL layer
     * does not expose a portable way to request that cast.
     *
     * @param requestId    target row PK
     * @param signalsJson  serialised {@code Map<SignalConfig, SignalState>} —
     *                     produced via the same {@code JsonMapper} the entity's
     *                     {@code @JdbcTypeCode(SqlTypes.JSON)} uses on the read path
     * @return number of rows updated; callers assert {@code == 1}
     */
    @Modifying
    @Query(value = """
            UPDATE enrollment_hub.enrollments
               SET signals = CAST(:signalsJson AS jsonb)
             WHERE request_id = :requestId
            """, nativeQuery = true)
    int updateSignals(@Param("requestId") UUID requestId,
                      @Param("signalsJson") String signalsJson);

    /**
     * Records the final decision: sets {@code decisionResult}, {@code decisionId},
     * and {@code decidedAt} in one statement, guarded by
     * {@code decisionResult IS NULL} so a second caller cannot overwrite a
     * decision that has already been recorded. Returns {@code 0} on that guard
     * (caller skips the publish path) and {@code 1} on success.
     *
     * <p>JPQL (not native) — the columns are scalar, no JSONB cast required.
     */
    @Modifying
    @Query("""
            UPDATE EnrollmentEntity e
               SET e.decisionResult = :decisionResult,
                   e.decisionId     = :decisionId,
                   e.decidedAt      = :decidedAt
             WHERE e.requestId      = :requestId
               AND e.decisionResult IS NULL
            """)
    int recordDecision(@Param("requestId") UUID requestId,
                       @Param("decisionResult") DecisionResult decisionResult,
                       @Param("decisionId") UUID decisionId,
                       @Param("decidedAt") Instant decidedAt);

    /**
     * Atomically claims a batch of expired-and-undecided correlation rows for the
     * timeout poller (ADR-010). Each row in the returned list is held under
     * {@code PESSIMISTIC_WRITE} until the enclosing transaction commits.
     *
     * <p>Rows already locked by another transaction (typically a result handler
     * holding a lock via {@link #findByRequestIdForUpdate(UUID)}) are
     * <b>skipped</b>, not waited on. This is what makes safe horizontal scaling
     * of the poller possible: N poller instances calling this method concurrently
     * partition the work cleanly because the rows each instance returns are
     * mutually disjoint.
     *
     * <p>The {@code "-2"} value on {@code jakarta.persistence.lock.timeout} is
     * the Jakarta Persistence sentinel for {@code SKIP LOCKED}, matching
     * {@code org.hibernate.LockOptions.SKIP_LOCKED}. The literal is used here
     * because {@code @QueryHint} requires a compile-time {@code String} constant.
     * If a future Hibernate major version reassigns this constant, the
     * {@code SkipLockedClaimIT} regression test fails loudly.
     *
     * @param now      cutoff; rows with {@code timeout_at <= now} are eligible
     * @param pageable batch sizer via {@code PageRequest.ofSize(N)}; small batches
     *                 keep per-transaction lock duration bounded
     * @return claimed rows ordered by {@code timeoutAt} ascending, mutually
     *         disjoint from rows returned to any other concurrent caller
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    @Query("""
            SELECT r FROM EnrollmentEntity r
             WHERE r.timeoutAt <= :now
               AND r.decisionResult IS NULL
             ORDER BY r.timeoutAt ASC
            """)
    List<EnrollmentEntity> claimPendingTimeouts(@Param("now") Instant now, Pageable pageable);

    /**
     * Read-only counterpart to {@link #claimPendingTimeouts(Instant, Pageable)}.
     * Returns every expired-and-undecided row without acquiring any lock; suitable
     * for diagnostics, dashboards, and tests that need to observe table state.
     * Not suitable for claim-and-process work — concurrent callers would race.
     */
    @Query("""
            SELECT r FROM EnrollmentEntity r
             WHERE r.timeoutAt <= :now
               AND r.decisionResult IS NULL
            """)
    List<EnrollmentEntity> findPendingTimeouts(@Param("now") Instant now);
}
