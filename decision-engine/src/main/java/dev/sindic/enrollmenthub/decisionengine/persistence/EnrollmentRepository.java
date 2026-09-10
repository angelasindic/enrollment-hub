package dev.sindic.enrollmenthub.decisionengine.persistence;

import dev.sindic.enrollmenthub.decisionengine.domain.IntakeStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the {@code enrollment_hub.enrollments} correlation table.
 *
 * <p>Three concurrency strategies coexist, each for a distinct race:
 * <ul>
 *   <li><b>Intake dedup</b> — {@link #insertIfAbsent}, {@code ON CONFLICT DO NOTHING} on the
 *       {@code enrollment_id} primary key (ADR-13).</li>
 *   <li><b>Handler coordination</b> — {@link #findByEnrollmentIdForUpdate},
 *       {@code PESSIMISTIC_WRITE} with WAIT, serialising two handlers settling different signals
 *       on one row (ADR-16).</li>
 *   <li><b>Batch claim</b> — {@link #claimPendingTimeouts} and {@link #claimUndispatched},
 *       {@code PESSIMISTIC_WRITE} with {@code SKIP LOCKED}, partitioning work across instances
 *       without blocking handlers (ADR-15, ADR-17).</li>
 * </ul>
 *
 * <p>The JSONB-bearing statements are native queries because PostgreSQL needs an explicit
 * {@code CAST(… AS jsonb)} on the bound text, which JPQL cannot express portably.
 */
public interface EnrollmentRepository extends JpaRepository<EnrollmentEntity, UUID> {

    /**
     * Idempotent intake insert (ADR-13). The primary key is the deduplicator, so a concurrent
     * redelivery is absorbed instead of raising a duplicate-key exception — no {@code existsById}
     * probe needed, this statement is its own fast path.
     *
     * @return {@code 1} if a row was inserted, {@code 0} if it already existed
     */
    @Modifying
    @Query(value = """
            INSERT INTO enrollment_hub.enrollments
                (enrollment_id, payment_type, original_request, signals, created_at, timeout_at)
            VALUES (:enrollmentId, :paymentType, CAST(:originalRequest AS jsonb),
                    CAST(:signalsJson AS jsonb), :createdAt, :timeoutAt)
            ON CONFLICT (enrollment_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("enrollmentId") UUID enrollmentId,
                       @Param("paymentType") String paymentType,
                       @Param("originalRequest") String originalRequest,
                       @Param("signalsJson") String signalsJson,
                       @Param("createdAt") Instant createdAt,
                       @Param("timeoutAt") Instant timeoutAt);

    /** Loads the row under {@code PESSIMISTIC_WRITE}, held until the transaction commits (ADR-16). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM EnrollmentEntity r WHERE r.enrollmentId = :enrollmentId")
    Optional<EnrollmentEntity> findByEnrollmentIdForUpdate(@Param("enrollmentId") UUID enrollmentId);

    /** Intake ledger state without loading the row (ADR-13). Empty when the row does not exist. */
    @Query("SELECT r.intakeStatus FROM EnrollmentEntity r WHERE r.enrollmentId = :enrollmentId")
    Optional<IntakeStatus> findIntakeStatus(@Param("enrollmentId") UUID enrollmentId);

    /**
     * Intake ledger {@code PENDING → COMPLETED}, once the per-signal commands are dispatched
     * (ADR-13). Unguarded, so a repeat call is a harmless no-op write that still reports one row.
     *
     * @return rows updated; callers expect {@code 1}
     */
    @Modifying
    @Query(value = """
            UPDATE enrollment_hub.enrollments
               SET intake_status = 'COMPLETED'
             WHERE enrollment_id = :enrollmentId
            """, nativeQuery = true)
    int markIntakeCompleted(@Param("enrollmentId") UUID enrollmentId);

    /**
     * Replaces the {@code signals} column by explicit {@code UPDATE} rather than JPA
     * dirty-tracking (ADR-16 §Write path); the returned row count is the persistence guarantee.
     *
     * @param signalsJson serialised {@code Map<SignalConfig, SignalState>}, written with the same
     *                    {@code JsonMapper} the entity's {@code @JdbcTypeCode(SqlTypes.JSON)} reads
     * @return rows updated; callers assert {@code == 1}
     */
    @Modifying
    @Query(value = """
            UPDATE enrollment_hub.enrollments
               SET signals = CAST(:signalsJson AS jsonb)
             WHERE enrollment_id = :enrollmentId
            """, nativeQuery = true)
    int updateSignals(@Param("enrollmentId") UUID enrollmentId,
                      @Param("signalsJson") String signalsJson);

    /**
     * Writes the final signals together with the decision columns in one statement, so the row is
     * never observable fully settled with a NULL decision (ADR-16 §finalize, ADR-17).
     *
     * <p>Guarded by {@code decision_result IS NULL}: a racing caller cannot overwrite a decision
     * already recorded, and gets {@code 0} back — its cue to skip the dispatch.
     *
     * @return {@code 1} on success, {@code 0} when the guard rejected
     */
    @Modifying
    @Query(value = """
            UPDATE enrollment_hub.enrollments
               SET signals         = CAST(:signalsJson AS jsonb),
                   decision_result = :decisionResult,
                   decision_id     = :decisionId,
                   decided_at      = :decidedAt
             WHERE enrollment_id      = :enrollmentId
               AND decision_result IS NULL
            """, nativeQuery = true)
    int completeWithDecision(@Param("enrollmentId") UUID enrollmentId,
                             @Param("signalsJson") String signalsJson,
                             // Bound as String, not the enum, because native queries lack
                             // the @Enumerated(STRING) metadata that JPQL infers from the
                             // entity field — default native binding would send the ordinal.
                             @Param("decisionResult") String decisionResult,
                             @Param("decisionId") UUID decisionId,
                             @Param("decidedAt") Instant decidedAt);

    /**
     * Claims a batch of expired-and-undecided rows for the timeout poller (ADR-15). Rows already
     * locked by a handler are skipped rather than waited on, so N pollers partition the work.
     *
     * <p>{@code "-2"} is the Jakarta Persistence sentinel for {@code SKIP LOCKED}
     * ({@code org.hibernate.LockOptions.SKIP_LOCKED}); a literal is required because
     * {@code @QueryHint} takes a compile-time constant. {@code SkipLockedClaimIT} fails loudly if
     * a future Hibernate reassigns it.
     *
     * @param now      cutoff; rows with {@code timeout_at <= now} are eligible
     * @param pageable batch sizer; small batches bound per-transaction lock duration
     * @return claimed rows, {@code timeoutAt} ascending, disjoint from other callers
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
     * Claims a batch of rows in the outbox state — {@code decision_result NOT NULL AND
     * dispatched_at IS NULL} — for the dispatch relay (ADR-17), same {@code SKIP LOCKED} idiom as
     * {@link #claimPendingTimeouts}. In steady state the eager after-commit dispatch has already
     * stamped every decided row, so this comes back empty off the partial index
     * {@code idx_enrollments_undispatched}.
     *
     * @param pageable batch sizer; small batches bound per-transaction lock duration
     * @return claimed rows, {@code decidedAt} ascending, disjoint from other callers
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    @Query("""
            SELECT r FROM EnrollmentEntity r
             WHERE r.decisionResult IS NOT NULL
               AND r.dispatchedAt IS NULL
             ORDER BY r.decidedAt ASC
            """)
    List<EnrollmentEntity> claimUndispatched(Pageable pageable);

    /**
     * Stamps the outbox marker, only ever after the broker's publisher confirm returns
     * (ADR-17 ordering: publish → await confirm → stamp). Guarded by {@code dispatched_at IS NULL}
     * so the eager dispatch and the relay cannot double-stamp a row they raced on; the loser sees
     * {@code 0}, which is not an error — both published the same {@code decision_id}.
     *
     * @return {@code 1} if this call stamped the row, {@code 0} if it was already stamped
     */
    @Modifying
    @Query("""
            UPDATE EnrollmentEntity r
               SET r.dispatchedAt = :dispatchedAt
             WHERE r.enrollmentId = :enrollmentId
               AND r.dispatchedAt IS NULL
            """)
    int markDispatched(@Param("enrollmentId") UUID enrollmentId,
                       @Param("dispatchedAt") Instant dispatchedAt);

    /**
     * Oldest {@code decided_at} still in the outbox state — the {@code decisionengine.outbox.oldest.age}
     * gauge behind the {@code StuckDecisionOutbox} alert (ADR-17). Empty in steady state, since the
     * eager dispatch stamps within milliseconds. Served by {@code idx_enrollments_undispatched}.
     */
    @Query("""
            SELECT MIN(r.decidedAt) FROM EnrollmentEntity r
             WHERE r.decisionResult IS NOT NULL
               AND r.dispatchedAt IS NULL
            """)
    Optional<Instant> findOldestUndispatchedDecidedAt();

    /**
     * Nulls {@code original_request} on a batch of delivered rows (ADR-20). {@code dispatched_at IS
     * NOT NULL} is the whole condition, and there is no waiting period: the payload's only reader
     * is the {@code EnrollmentDecisionEvent}, that event was confirmed by the broker before the
     * stamp, and from the confirm onward it and its retention belong to the Account Service, which
     * owns the queue it lands on (ADR-13 §Channel Ownership).
     *
     * <p>The predicate is the exact complement of every state that still has a reader here:
     * {@link #claimUndispatched} selects {@code dispatched_at IS NULL} and {@link #markDispatched}
     * is guarded on it, so no row this statement touches can be re-claimed or re-published, and no
     * row the outbox or the timeout poller can claim is reachable from here at any age.
     *
     * <p>Native, and batched through a {@code SKIP LOCKED} subselect rather than the
     * {@code @Lock} + {@code Pageable} idiom used by the claim methods — those return entities for
     * a caller to process, while this is a set-based write that must not load rows to perform it.
     * Transactional here rather than at a service: the statement is the whole unit of work, so a
     * wrapper would add a layer and no boundary.
     *
     * @param batchSize rows per statement; the caller drains while this equals {@code batchSize}
     * @return rows stripped
     */
    @Transactional
    @Modifying
    @Query(value = """
            UPDATE enrollment_hub.enrollments
               SET original_request = NULL
             WHERE enrollment_id IN (
                   SELECT enrollment_id
                     FROM enrollment_hub.enrollments
                    WHERE original_request IS NOT NULL
                      AND dispatched_at IS NOT NULL
                    ORDER BY dispatched_at
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED)
            """, nativeQuery = true)
    int stripDispatchedPayloads(@Param("batchSize") int batchSize);

    /**
     * Intake time of the oldest row still holding an enrollment payload — the
     * {@code decisionengine.payload.oldest.age} gauge (ADR-20). This is the compliance reading:
     * it answers "how long has this service been holding personal data" directly, rather than by
     * inference from the retention job's counters. Empty when no row holds a payload.
     *
     * <p>Served by {@code idx_enrollments_payload_held}, which indexes only payload-bearing rows.
     * A healthy value is bounded by how long a row legitimately holds a payload: until it is
     * decided and delivered, then until the next retention pass. An undecided row stuck past its
     * timeout raises it too, which is intended — that row is also holding PII.
     */
    @Query("""
            SELECT MIN(r.createdAt) FROM EnrollmentEntity r
             WHERE r.originalRequest IS NOT NULL
            """)
    Optional<Instant> findOldestHeldPayloadCreatedAt();

    /**
     * Lock-free counterpart to {@link #claimPendingTimeouts} for observing table state; unusable
     * for claim-and-process work, where concurrent callers would race. No production caller —
     * currently referenced only by tests.
     */
    @Query("""
            SELECT r FROM EnrollmentEntity r
             WHERE r.timeoutAt <= :now
               AND r.decisionResult IS NULL
            """)
    List<EnrollmentEntity> findPendingTimeouts(@Param("now") Instant now);
}
