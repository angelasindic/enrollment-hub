package dev.sindic.enrollmenthub.decisionengine.persistence;

import dev.sindic.enrollmenthub.decisionengine.domain.DecisionResult;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the {@code enrollment_hub.enrollments} correlation table.
 *
 * <p>Three concurrency strategies coexist, each for a distinct race; the rationale
 * lives in the ADRs, the methods just point at it:
 * <ul>
 *   <li><b>Intake dedup</b> — {@link #insertIfAbsent} relies on the {@code enrollment_id}
 *       primary key via {@code ON CONFLICT DO NOTHING} (ADR-13 §Ingress Inversion).</li>
 *   <li><b>Handler coordination</b> — {@link #findByEnrollmentIdForUpdate}
 *       ({@code PESSIMISTIC_WRITE}, WAIT) serialises two handlers settling different
 *       signals on the same row (ADR-16).</li>
 *   <li><b>Poller claim</b> — {@link #claimPendingTimeouts} ({@code PESSIMISTIC_WRITE},
 *       {@code SKIP LOCKED}) lets pollers partition expired rows without blocking
 *       handlers (ADR-15).</li>
 * </ul>
 * {@link #findPendingTimeouts} is the read-only, lock-free variant for diagnostics.
 */
public interface EnrollmentRepository extends JpaRepository<EnrollmentEntity, UUID> {

    /**
     * Idempotent intake insert as a single atomic statement. {@code ON CONFLICT
     * (enrollment_id) DO NOTHING} makes the {@code enrollment_id} primary key the
     * authoritative deduplicator: a concurrent redelivery of the same id cannot
     * raise a duplicate-key exception — the losing insert is absorbed and reported
     * as zero rows. No prior {@code existsById} probe is needed; this statement is
     * its own fast-path.
     *
     * <p>Native query because {@code ON CONFLICT} is PostgreSQL-specific and the
     * two JSONB columns need an explicit {@code ::jsonb} cast (same reason as
     * {@link #updateSignals}).
     *
     * @return {@code 1} if a new row was inserted; {@code 0} if the row already
     *         existed (idempotent redelivery)
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

    /**
     * Loads the correlation record with a {@code PESSIMISTIC_WRITE} lock.
     * The row lock is held until the enclosing transaction commits, preventing
     * concurrent handlers from reading or writing the same row.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM EnrollmentEntity r WHERE r.enrollmentId = :enrollmentId")
    Optional<EnrollmentEntity> findByEnrollmentIdForUpdate(@Param("enrollmentId") UUID enrollmentId);

    /**
     * Reads the intake idempotency-ledger state without loading the full row
     * (ADR-13 §Ingress Inversion). Empty when the correlation record does not exist.
     */
    @Query("SELECT r.intakeStatus FROM EnrollmentEntity r WHERE r.enrollmentId = :enrollmentId")
    Optional<IntakeStatus> findIntakeStatus(@Param("enrollmentId") UUID enrollmentId);

    /**
     * Transitions the intake ledger {@code PENDING → COMPLETED} after the per-signal
     * commands have been dispatched (ADR-13 §Ingress Inversion). Idempotent: a second
     * call on an already-COMPLETED row is a no-op write that still reports one row.
     *
     * @return number of rows updated; the caller expects {@code 1}
     */
    @Modifying
    @Query(value = """
            UPDATE enrollment_hub.enrollments
               SET intake_status = 'COMPLETED'
             WHERE enrollment_id = :enrollmentId
            """, nativeQuery = true)
    int markIntakeCompleted(@Param("enrollmentId") UUID enrollmentId);

    /**
     * Replaces the {@code signals} JSONB column with the given serialized value.
     * Per ADR-16 §Write path, the JSON-mapped collection column is written via an explicit
     * SQL {@code UPDATE} rather than via JPA dirty-tracking; the returned row
     * count is the persistence guarantee.
     *
     * <p>Native query because PostgreSQL needs an explicit {@code ::jsonb} (or
     * {@code CAST(… AS jsonb)}) on the bound text parameter; the JPQL layer
     * does not expose a portable way to request that cast.
     *
     * @param enrollmentId    target row PK
     * @param signalsJson  serialised {@code Map<SignalConfig, SignalState>} —
     *                     produced via the same {@code JsonMapper} the entity's
     *                     {@code @JdbcTypeCode(SqlTypes.JSON)} uses on the read path
     * @return number of rows updated; callers assert {@code == 1}
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
     * Single-statement completion: writes the final signals JSON together with the
     * decision columns ({@code decisionResult}, {@code decisionId},
     * {@code decidedAt}). Used when the just-applied signal transition completes
     * the process — collapses what would otherwise be two UPDATEs into one and
     * removes the intra-row state where signals are settled but the decision is
     * still NULL.
     *
     * <p>Guarded by {@code decisionResult IS NULL} so a second caller racing on
     * the same row cannot overwrite a decision that has already been recorded.
     * Returns {@code 0} on that guard (caller skips the publish path) and
     * {@code 1} on success.
     *
     * <p>Native query because PostgreSQL needs the explicit {@code ::jsonb} cast
     * on the signals parameter (same reason as {@link #updateSignals}).
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
     * Atomically claims a batch of expired-and-undecided rows for the timeout poller
     * (ADR-15), each held under {@code PESSIMISTIC_WRITE} until the transaction commits.
     * Rows already locked by a handler are <b>skipped</b>, not waited on, so N pollers
     * partition the work into disjoint sets (safe horizontal scaling).
     *
     * <p>The {@code "-2"} on {@code jakarta.persistence.lock.timeout} is the Jakarta
     * Persistence sentinel for {@code SKIP LOCKED} ({@code org.hibernate.LockOptions.SKIP_LOCKED});
     * a literal is required because {@code @QueryHint} takes a compile-time constant. If a
     * future Hibernate reassigns it, the {@code SkipLockedClaimIT} regression test fails loudly.
     *
     * @param now      cutoff; rows with {@code timeout_at <= now} are eligible
     * @param pageable batch sizer; small batches bound per-transaction lock duration
     * @return claimed rows by {@code timeoutAt} ascending, disjoint from other callers
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
