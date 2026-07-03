package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.decisionengine.amqp.EnrollmentDecisionPublisher;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentEntity;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.UUID;

/**
 * The single publish path for {@code EnrollmentDecisionEvent} (ADR-17). Reads the frozen
 * decision off the correlation row, publishes it with publisher confirms, and stamps
 * {@code dispatched_at} only after the confirm returns — never recomputing, so every retry
 * emits a byte-identical event under the same {@code decisionId}.
 *
 * <p>Two triggers converge here:
 * <ul>
 *   <li><b>Eager</b> — {@link #dispatchNow(UUID)}, invoked from the {@code afterCommit} hook
 *       {@code EnrollmentService.finalizeDecision} registers. Owns steady-state latency:
 *       the decision leaves within milliseconds of the decide commit, after the row lock is
 *       released. Runs in its own transaction ({@code REQUIRES_NEW} — required for
 *       transactional work started from an after-commit synchronization).</li>
 *   <li><b>Backstop</b> — {@link #dispatchPending(int)}, invoked by the {@link EnrollmentSweepJob} dispatch phase
 *       on a loose schedule. The durability backstop: claims rows whose eager dispatch failed
 *       or whose process crashed between the decide commit and the publish.</li>
 * </ul>
 *
 * <p>The {@code dispatched_at IS NULL} guard on the stamp makes the eager/backstop race benign:
 * if both publish, both events carry the same frozen {@code decisionId}, the second stamp is
 * a no-op, and consumer dedup absorbs the duplicate (ADR-17 §Division of labour).
 */
@Service
@Slf4j
public class DecisionDispatcher {

    private final EnrollmentRepository repository;
    private final EnrollmentDecisionPublisher publisher;
    private final DecisionEventMapper decisionEventMapper;
    private final Clock clock;

    DecisionDispatcher(EnrollmentRepository repository,
                       EnrollmentDecisionPublisher publisher,
                       JsonMapper jsonMapper,
                       Clock clock) {
        this.repository = repository;
        this.publisher = publisher;
        this.decisionEventMapper = new DecisionEventMapper(jsonMapper);
        this.clock = clock;
    }

    /**
     * Eager trigger. Re-reads the row (committed state — the decide transaction has already
     * committed when the after-commit hook fires) and dispatches it unless the relay got there
     * first. No row lock is taken: the guarded stamp resolves the race. A publish failure
     * propagates to the hook, which logs it and leaves the row for the relay.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatchNow(UUID enrollmentId) {
        var entity = repository.findById(enrollmentId).orElse(null);
        if (entity == null || entity.getDecisionResult() == null) {
            // Structurally impossible — the hook is registered only after the decide UPDATE
            // committed. Log rather than throw: the relay claim would find nothing either.
            log.warn("Eager dispatch found no decided row for enrollmentId={}", enrollmentId);
            return;
        }
        if (entity.getDispatchedAt() != null) {
            return;
        }
        dispatch(entity);
    }

    /**
     * Relay trigger. Claims a batch of decided-but-undispatched rows under
     * {@code PESSIMISTIC_WRITE} + {@code SKIP LOCKED} and dispatches each. A publish failure
     * rolls back the whole batch's stamps; the re-claim next tick re-publishes byte-identical
     * duplicates (ADR-17 crash-window table).
     *
     * @return number of rows dispatched; the caller drains while this equals {@code batchSize}
     */
    @Transactional
    public int dispatchPending(int batchSize) {
        var undispatched = repository.claimUndispatched(PageRequest.ofSize(batchSize));
        undispatched.forEach(this::dispatch);
        if (!undispatched.isEmpty()) {
            log.info("Dispatch relay published {} decision(s)", undispatched.size());
        }
        return undispatched.size();
    }

    private void dispatch(EnrollmentEntity entity) {
        publisher.publish(decisionEventMapper.buildDecisionEvent(
                entity,
                entity.getSignals(),
                entity.getDecisionResult(),
                entity.getDecisionId(),
                entity.getDecidedAt()));

        int rows = repository.markDispatched(entity.getEnrollmentId(), clock.instant());
        if (rows == 0) {
            log.info("dispatched_at already stamped for enrollmentId={} — concurrent trigger "
                    + "published a byte-identical duplicate; absorbed by decisionId dedup",
                    entity.getEnrollmentId());
        }
    }
}
