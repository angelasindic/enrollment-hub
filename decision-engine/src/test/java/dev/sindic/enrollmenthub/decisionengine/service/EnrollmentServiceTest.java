package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.contracts.events.EnrollmentDecisionEvent;
import dev.sindic.enrollmenthub.decisionengine.amqp.EnrollmentDecisionPublisher;
import dev.sindic.enrollmenthub.decisionengine.domain.*;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentRepository;
import dev.sindic.enrollmenthub.decisionengine.TestEntityFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class EnrollmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-13T12:00:00Z");
    private static final Instant TIMEOUT = NOW.plusSeconds(60);

    @Mock EnrollmentRepository repository;
    @Mock EnrollmentDecisionPublisher publisher;

    EnrollmentService service;

    @BeforeEach
    void setUp() {
        service = new EnrollmentService(repository, publisher,
                tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build());
    }

    @Test
    void recordSignalResult_updatesEntityButDoesNotPublishWhenNotComplete() {
        var requestId = UUID.randomUUID();
        var entity = TestEntityFactory.creditCard(requestId, NOW, TIMEOUT);
        given(repository.findByRequestIdForUpdate(requestId)).willReturn(Optional.of(entity));

        service.recordSignalResult(requestId, SignalConfig.GEO_SCORE,
                SignalState.settled(RiskLevel.HIGH));

        assertThat(entity.getSignals().get(SignalConfig.GEO_SCORE).processingState())
                .isEqualTo(SignalProcessingState.SETTLED);
        assertThat(entity.getSignals().get(SignalConfig.GEO_SCORE).riskLevel()).isEqualTo(RiskLevel.HIGH);
        then(publisher).should(never()).publish(any());
    }

    @Test
    void recordSignalResult_completesAndPublishesDecision() {
        var requestId = UUID.randomUUID();
        var entity = TestEntityFactory.creditCard(requestId, NOW, TIMEOUT);
        entity.recordSignalResult(SignalConfig.FRAUD_CHECK, SignalState.settled(SignalOutcome.OK));
        given(repository.findByRequestIdForUpdate(requestId)).willReturn(Optional.of(entity));

        service.recordSignalResult(requestId, SignalConfig.GEO_SCORE,
                SignalState.settled(RiskLevel.LOW));

        var captor = ArgumentCaptor.forClass(EnrollmentDecisionEvent.class);
        then(publisher).should().publish(captor.capture());

        var event = captor.getValue();
        assertThat(event.decisionId()).isNotNull();
        assertThat(event.decisionResult())
                .isEqualTo(dev.sindic.enrollmenthub.contracts.events.DecisionResult.APPROVED);
        assertThat(event.signals().get("GEO_SCORE").riskLevel())
                .isEqualTo(dev.sindic.enrollmenthub.contracts.events.RiskLevel.LOW);
        assertThat(event.signals().get("FRAUD_CHECK").outcome())
                .isEqualTo(dev.sindic.enrollmenthub.contracts.events.SignalOutcome.OK);
        assertThat(event.decidedAt()).isNotNull();
    }

    @Test
    void recordSignalResult_idempotentDiscard() {
        var requestId = UUID.randomUUID();
        var entity = TestEntityFactory.creditCard(requestId, NOW, TIMEOUT);
        entity.recordSignalResult(SignalConfig.GEO_SCORE, SignalState.settled(RiskLevel.LOW));
        given(repository.findByRequestIdForUpdate(requestId)).willReturn(Optional.of(entity));

        service.recordSignalResult(requestId, SignalConfig.GEO_SCORE,
                SignalState.settled(RiskLevel.HIGH));

        assertThat(entity.getSignals().get(SignalConfig.GEO_SCORE).riskLevel()).isEqualTo(RiskLevel.LOW);
        then(publisher).should(never()).publish(any());
    }

    @Test
    void recordSignalResult_unknownRequestIdThrowsUnknownCorrelation() {
        var requestId = UUID.randomUUID();
        given(repository.findByRequestIdForUpdate(requestId)).willReturn(Optional.empty());

        // Distinct exception type so the AMQP advice chain can mark it non-retryable
        // (see AmqpConfig#listenerRetryPolicy). Asserting the concrete type, not
        // just RuntimeException, is what couples this test to the retry policy contract.
        assertThatThrownBy(() -> service.recordSignalResult(requestId, SignalConfig.GEO_SCORE,
                SignalState.settled(RiskLevel.LOW)))
                .isInstanceOf(UnknownCorrelationException.class)
                .hasMessageContaining(requestId.toString());

        then(publisher).should(never()).publish(any());
    }

    @Test
    void recordSignalResult_publisherFailurePropagates() {
        var requestId = UUID.randomUUID();
        var entity = TestEntityFactory.creditCard(requestId, NOW, TIMEOUT);
        entity.recordSignalResult(SignalConfig.FRAUD_CHECK, SignalState.settled(SignalOutcome.OK));
        given(repository.findByRequestIdForUpdate(requestId)).willReturn(Optional.of(entity));
        doThrow(new RuntimeException("broker down")).when(publisher).publish(any());

        assertThatThrownBy(() -> service.recordSignalResult(requestId, SignalConfig.GEO_SCORE,
                SignalState.settled(RiskLevel.LOW)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("broker down");
    }
}
