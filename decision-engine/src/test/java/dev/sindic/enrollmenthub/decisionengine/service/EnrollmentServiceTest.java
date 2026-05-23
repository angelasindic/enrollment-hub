package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.contracts.events.EnrollmentDecisionEvent;
import dev.sindic.enrollmenthub.decisionengine.amqp.EnrollmentDecisionPublisher;
import dev.sindic.enrollmenthub.decisionengine.domain.DecisionResult;
import dev.sindic.enrollmenthub.decisionengine.domain.RiskLevel;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalConfig;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalOutcome;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalProcessingState;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalState;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentRepository;
import dev.sindic.enrollmenthub.decisionengine.TestEntityFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class EnrollmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-13T12:00:00Z");
    private static final Instant TIMEOUT = NOW.plusSeconds(60);
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-04-13T12:00:42Z"), ZoneOffset.UTC);

    @Mock EnrollmentRepository repository;
    @Mock EnrollmentDecisionPublisher publisher;

    EnrollmentService service;

    @BeforeEach
    void setUp() {
        service = new EnrollmentService(
                repository,
                publisher,
                tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build(),
                FIXED_CLOCK);
    }

    @Test
    void recordSignalResult_writesJsonSignalsViaExplicitUpdate_whenNotComplete() {
        // GIVEN a CREDIT_CARD entity in initial PENDING/PENDING state.
        var requestId = UUID.randomUUID();
        var entity = TestEntityFactory.creditCard(requestId, NOW, TIMEOUT);
        given(repository.findByRequestIdForUpdate(requestId)).willReturn(Optional.of(entity));
        given(repository.updateSignals(eq(requestId), anyString())).willReturn(1);

        // WHEN geo settles HIGH (fraud is still PENDING — not yet complete).
        service.recordSignalResult(requestId, SignalConfig.GEO_SCORE,
                SignalState.settled(RiskLevel.HIGH));

        // THEN the new signals JSON went to the explicit UPDATE; no decision recorded; no publish.
        var jsonCaptor = ArgumentCaptor.forClass(String.class);
        then(repository).should().updateSignals(eq(requestId), jsonCaptor.capture());
        assertThat(jsonCaptor.getValue())
                .as("explicit UPDATE carries the post-transition signal map")
                .contains("\"GEO_SCORE\"")
                .contains("\"SETTLED\"")
                .contains("\"HIGH\"")
                .contains("\"FRAUD_CHECK\"")
                .contains("\"PENDING\"");
        then(repository).should(never()).completeWithDecision(any(), any(), any(), any(), any());
        then(publisher).should(never()).publish(any());
    }

    @Test
    void recordSignalResult_completesAndPublishesDecision_whenAllSignalsSettle() {
        // GIVEN a CREDIT_CARD entity with FRAUD already settled OK.
        var requestId = UUID.randomUUID();
        var entity = TestEntityFactory.creditCard(requestId, NOW, TIMEOUT);
        entity.getSignals().put(SignalConfig.FRAUD_CHECK, SignalState.settled(SignalOutcome.OK));
        given(repository.findByRequestIdForUpdate(requestId)).willReturn(Optional.of(entity));
        given(repository.completeWithDecision(eq(requestId), anyString(), any(), any(), any())).willReturn(1);

        // WHEN geo settles LOW — both signals now settled, decision fires.
        service.recordSignalResult(requestId, SignalConfig.GEO_SCORE,
                SignalState.settled(RiskLevel.LOW));

        // THEN a single combined UPDATE writes signals + decision; no separate updateSignals.
        then(repository).should(never()).updateSignals(any(), any());

        var signalsJsonCaptor = ArgumentCaptor.forClass(String.class);
        var decisionResultCaptor = ArgumentCaptor.forClass(String.class);
        var decisionIdCaptor = ArgumentCaptor.forClass(UUID.class);
        var decidedAtCaptor = ArgumentCaptor.forClass(Instant.class);
        then(repository).should().completeWithDecision(
                eq(requestId),
                signalsJsonCaptor.capture(),
                decisionResultCaptor.capture(),
                decisionIdCaptor.capture(),
                decidedAtCaptor.capture());
        assertThat(signalsJsonCaptor.getValue())
                .as("combined UPDATE carries the post-transition signal map")
                .contains("\"GEO_SCORE\"").contains("\"SETTLED\"").contains("\"LOW\"")
                .contains("\"FRAUD_CHECK\"").contains("\"OK\"");
        assertThat(decisionResultCaptor.getValue()).isEqualTo(DecisionResult.APPROVED.name());
        assertThat(decisionIdCaptor.getValue()).isNotNull();
        assertThat(decidedAtCaptor.getValue()).isEqualTo(FIXED_CLOCK.instant());

        var eventCaptor = ArgumentCaptor.forClass(EnrollmentDecisionEvent.class);
        then(publisher).should().publish(eventCaptor.capture());
        var event = eventCaptor.getValue();
        assertThat(event.decisionId()).isEqualTo(decisionIdCaptor.getValue());
        assertThat(event.decidedAt()).isEqualTo(FIXED_CLOCK.instant());
        assertThat(event.decisionResult())
                .isEqualTo(dev.sindic.enrollmenthub.contracts.events.DecisionResult.APPROVED);
        assertThat(event.signals().get("GEO_SCORE").riskLevel())
                .isEqualTo(dev.sindic.enrollmenthub.contracts.events.RiskLevel.LOW);
        assertThat(event.signals().get("FRAUD_CHECK").outcome())
                .isEqualTo(dev.sindic.enrollmenthub.contracts.events.SignalOutcome.OK);
    }

    @Test
    void recordSignalResult_idempotentDiscard_whenSignalAlreadySettled() {
        var requestId = UUID.randomUUID();
        var entity = TestEntityFactory.creditCard(requestId, NOW, TIMEOUT);
        entity.getSignals().put(SignalConfig.GEO_SCORE, SignalState.settled(RiskLevel.LOW));
        given(repository.findByRequestIdForUpdate(requestId)).willReturn(Optional.of(entity));

        service.recordSignalResult(requestId, SignalConfig.GEO_SCORE,
                SignalState.settled(RiskLevel.HIGH));

        // No write, no decision, no publish — silent idempotent return.
        then(repository).should(never()).updateSignals(any(), any());
        then(repository).should(never()).completeWithDecision(any(), any(), any(), any(), any());
        then(publisher).should(never()).publish(any());
    }

    @Test
    void recordSignalResult_throwsUnknownCorrelation_whenRowDoesNotExist() {
        var requestId = UUID.randomUUID();
        given(repository.findByRequestIdForUpdate(requestId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordSignalResult(requestId, SignalConfig.GEO_SCORE,
                SignalState.settled(RiskLevel.LOW)))
                .isInstanceOf(UnknownCorrelationException.class)
                .hasMessageContaining(requestId.toString());

        then(repository).should(never()).updateSignals(any(), any());
        then(publisher).should(never()).publish(any());
    }

    @Test
    void recordSignalResult_throwsWhenUpdateSignalsAffectsZeroRows() {
        // Defensive: a row that exists at SELECT FOR UPDATE time but vanishes
        // before the UPDATE is structurally impossible under our row lock —
        // but the row-count assertion turns "impossible drift" into a loud
        // failure rather than a silent missed write (ADR-015 §Write path).
        var requestId = UUID.randomUUID();
        var entity = TestEntityFactory.creditCard(requestId, NOW, TIMEOUT);
        given(repository.findByRequestIdForUpdate(requestId)).willReturn(Optional.of(entity));
        given(repository.updateSignals(eq(requestId), anyString())).willReturn(0);

        assertThatThrownBy(() -> service.recordSignalResult(requestId, SignalConfig.GEO_SCORE,
                SignalState.settled(RiskLevel.LOW)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(requestId.toString())
                .hasMessageContaining("0");

        then(repository).should(never()).completeWithDecision(any(), any(), any(), any(), any());
        then(publisher).should(never()).publish(any());
    }

    @Test
    void recordSignalResult_skipsPublishWhenCompleteWithDecisionAffectsZeroRows() {
        // Edge case: the decision_result IS NULL guard rejected the combined
        // UPDATE, meaning a parallel path already recorded the decision.
        // Under our PESSIMISTIC_WRITE lock this is structurally impossible,
        // but if it does happen we must not double-publish.
        var requestId = UUID.randomUUID();
        var entity = TestEntityFactory.creditCard(requestId, NOW, TIMEOUT);
        entity.getSignals().put(SignalConfig.FRAUD_CHECK, SignalState.settled(SignalOutcome.OK));
        given(repository.findByRequestIdForUpdate(requestId)).willReturn(Optional.of(entity));
        given(repository.completeWithDecision(eq(requestId), anyString(), any(), any(), any())).willReturn(0);

        service.recordSignalResult(requestId, SignalConfig.GEO_SCORE,
                SignalState.settled(RiskLevel.LOW));

        then(publisher).should(never()).publish(any());
    }

    @Test
    void recordSignalResult_propagatesPublisherFailure_soTxRollsBack() {
        // Inside the @Transactional method a publisher exception rolls back the
        // explicit UPDATE we just issued — that's the ADR-015 contract.
        var requestId = UUID.randomUUID();
        var entity = TestEntityFactory.creditCard(requestId, NOW, TIMEOUT);
        entity.getSignals().put(SignalConfig.FRAUD_CHECK, SignalState.settled(SignalOutcome.OK));
        given(repository.findByRequestIdForUpdate(requestId)).willReturn(Optional.of(entity));
        given(repository.completeWithDecision(eq(requestId), anyString(), any(), any(), any())).willReturn(1);
        doThrow(new RuntimeException("broker down")).when(publisher).publish(any());

        assertThatThrownBy(() -> service.recordSignalResult(requestId, SignalConfig.GEO_SCORE,
                SignalState.settled(RiskLevel.LOW)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("broker down");
    }

    @Test
    void recordSignalResult_treatsFailedSignalStateAsAlreadySettled() {
        // A FAILED (timeout) signal is still "not PENDING" — the idempotency
        // guard must skip the late-arriving result rather than overwrite.
        var requestId = UUID.randomUUID();
        var entity = TestEntityFactory.creditCard(requestId, NOW, TIMEOUT);
        entity.getSignals().put(SignalConfig.GEO_SCORE,
                new SignalState(SignalProcessingState.FAILED, null, null, "timeout"));
        given(repository.findByRequestIdForUpdate(requestId)).willReturn(Optional.of(entity));

        service.recordSignalResult(requestId, SignalConfig.GEO_SCORE,
                SignalState.settled(RiskLevel.LOW));

        then(repository).should(never()).updateSignals(any(), any());
        then(publisher).should(never()).publish(any());
    }
}
