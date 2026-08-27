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
 * The single publish path for {@code EnrollmentDecisionEvent} (ADR-17). Reads the decision off
 * the correlation row and never recomputes it, so every attempt emits the same event under the
 * same {@code decisionId}.
 *
 * <p>Two triggers converge here: {@link #dispatchNow} from the after-commit hook, which owns
 * steady-state latency and runs {@code REQUIRES_NEW} because the surrounding transaction has
 * already committed; and {@link #dispatchPending}, the relay driven by {@link EnrollmentSweepJob}
 * on a loose schedule, which covers rows whose eager dispatch failed or was lost to a crash.
 *
 * <p>Their race is benign by construction: the {@code dispatched_at IS NULL} guard on the stamp
 * means the loser is a no-op, and both published the same {@code decisionId} for consumers to
 * dedup on (ADR-17 §Division of labour).
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
     * Eager trigger. Re-reads the committed row and dispatches unless the relay got there first.
     * No row lock — the guarded stamp settles the race. A publish failure propagates to the hook,
     * which logs and leaves the row for the relay.
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
     * Relay trigger. Claims a batch of rows in the outbox state and dispatches each. A publish
     * failure rolls back the whole batch's stamps; the next tick re-claims and re-publishes
     * identical duplicates (ADR-17 crash-window table).
     *
     * @return rows dispatched; the caller drains while this equals {@code batchSize}
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
