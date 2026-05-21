package dev.sindic.enrollmenthub.decisionengine.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the {@code enrollment_hub.enrollments} correlation table.
 * <p>
 * Concurrent result handlers use this repository following the ADR-016 protocol:
 * <ol>
 *   <li>{@link #findByRequestIdForUpdate(UUID)} — acquires a {@code SELECT FOR UPDATE}
 *       row lock, serializing concurrent handlers on the same request.</li>
 *   <li>The handler mutates the locked entity via
 *       {@link EnrollmentEntity#recordSignalResult} which includes
 *       an idempotency guard (returns {@code false} if already settled).</li>
 *   <li>The handler evaluates {@link EnrollmentEntity#isComplete()} on the
 *       in-memory entity. Hibernate dirty-checking flushes the UPDATE on commit.</li>
 * </ol>
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
     * Finds correlation records that have passed their deadline and still have
     * no final decision. Used by the scheduled timeout poller (ADR-010).
     */
    @Query("""
            SELECT r FROM EnrollmentEntity r
             WHERE r.timeoutAt <= :now
               AND r.decisionResult IS NULL
            """)
    List<EnrollmentEntity> findPendingTimeouts(@Param("now") Instant now);
}
