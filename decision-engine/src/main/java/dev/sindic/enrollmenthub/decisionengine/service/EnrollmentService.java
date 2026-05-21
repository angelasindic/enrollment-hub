package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.decisionengine.amqp.EnrollmentDecisionPublisher;
import dev.sindic.enrollmenthub.decisionengine.domain.DecisionEngine;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalConfig;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalState;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentEntity;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * Asynchronous convergence service for the scatter-gather pipeline.
 *
 * <p>{@link #recordSignalResult} follows the ADR-016 protocol:
 * <ol>
 *   <li>Acquire pessimistic row lock ({@code SELECT FOR UPDATE})</li>
 *   <li>Record the signal result (idempotent — duplicate deliveries are discarded)</li>
 *   <li>If all applicable signals have settled, run the decision engine and
 *       publish {@code EnrollmentDecisionEvent}</li>
 * </ol>
 * Publish happens inside the transaction: if it fails, the transaction rolls
 * back and the inbound AMQP message is retried.
 *
 * @see CreateEnrollmentService synchronous intake counterpart
 */
@Service
@Slf4j
public class EnrollmentService {

    private final EnrollmentRepository repository;
    private final EnrollmentDecisionPublisher publisher;
    private final DecisionEventMapper decisionEventMapper;

    EnrollmentService(EnrollmentRepository repository,
                      EnrollmentDecisionPublisher publisher,
                      JsonMapper jsonMapper) {
        this.repository = repository;
        this.publisher = publisher;
        this.decisionEventMapper = new DecisionEventMapper(jsonMapper);
    }

    @Transactional
    public void recordSignalResult(UUID requestId, SignalConfig signal, SignalState state) {
        var entity = repository.findByRequestIdForUpdate(requestId)
                .orElseThrow(() -> new UnknownCorrelationException(requestId));

        boolean applied = entity.recordSignalResult(signal, state);
        if (!applied) {
            log.warn("{} already recorded — idempotent discard", signal);
            return;
        }

        evaluateAndPublishIfComplete(entity);
    }

    private void evaluateAndPublishIfComplete(EnrollmentEntity entity) {
        if (!entity.isComplete()) return;

        var result = DecisionEngine.evaluate(entity.toDomainForDecision());
        entity.recordDecision(result.decision(), Instant.now());
        publisher.publish(decisionEventMapper.buildDecisionEvent(entity));
    }
}
