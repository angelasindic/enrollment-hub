package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.decisionengine.domain.DecisionResult;
import dev.sindic.enrollmenthub.decisionengine.domain.RiskLevel;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalConfig;
import dev.sindic.enrollmenthub.decisionengine.domain.CheckOutcome;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalState;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentRepository;
import dev.sindic.enrollmenthub.decisionengine.TestEntityFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
    @Mock DecisionDispatcher dispatcher;

    SimpleMeterRegistry meterRegistry;
    EnrollmentService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new EnrollmentService(
                repository,
                dispatcher,
                new SignalSettlementMetrics(meterRegistry),
                tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build(),
                FIXED_CLOCK);
        // The production callers run inside @Transactional; the finalize step registers an
        // afterCommit synchronization there. Activate synchronization so registration works,
        // and simulate the commit explicitly via simulateCommit().
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    /** Fires the afterCommit callbacks the service registered — the unit-test stand-in for a commit. */
    private static void simulateCommit() {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
    }

    @Test
    void recordSignalResult_mismatchedShape_rejectedWithoutTouchingTheRow() {
        // GEO_SCORE is SCORING_SIGNAL, so a check-style verdict is not a state it can hold.
        // Aggregation would match no branch for it and the signal would count for nothing.
        assertThatThrownBy(() -> service.recordSignalResult(
                UUID.randomUUID(), SignalConfig.GEO_SCORE, new SignalState.Checked(CheckOutcome.FAILED)))
                .isInstanceOf(SignalShapeMismatchException.class)
                .hasMessageContaining("GEO_SCORE")
                .hasMessageContaining("SCORING_SIGNAL");

        // The row is not what is wrong, so it is never read or locked.
        then(repository).should(never()).findByEnrollmentIdForUpdate(any());
        then(repository).should(never()).updateSignals(any(), any());
    }

    @Test
    void recordSignalResult_resultlessStatesAreAdmittedByAnySignal() {
        // NotExecuted comes from the timeout policy and applies to every classification; the guard
        // must not reject the states that say a signal produced nothing.
        var enrollmentId = UUID.randomUUID();
        var entity = TestEntityFactory.creditCard(enrollmentId, NOW, TIMEOUT);
        given(repository.findByEnrollmentIdForUpdate(enrollmentId)).willReturn(Optional.of(entity));
        given(repository.updateSignals(eq(enrollmentId), anyString())).willReturn(1);

        service.recordSignalResult(
                enrollmentId, SignalConfig.GEO_SCORE, new SignalState.NotExecuted("timeout"));

        then(repository).should().updateSignals(eq(enrollmentId), anyString());
    }

    @Test
    void recordSignalResult_writesJsonSignalsViaExplicitUpdate_whenNotComplete() {
        // GIVEN a CREDIT_CARD entity in initial PENDING/PENDING state.
        var enrollmentId = UUID.randomUUID();
        var entity = TestEntityFactory.creditCard(enrollmentId, NOW, TIMEOUT);
        given(repository.findByEnrollmentIdForUpdate(enrollmentId)).willReturn(Optional.of(entity));
        given(repository.updateSignals(eq(enrollmentId), anyString())).willReturn(1);

        // WHEN geo settles HIGH (fraud is still PENDING — not yet complete).
        service.recordSignalResult(enrollmentId, SignalConfig.GEO_SCORE,
                new SignalState.Scored(RiskLevel.HIGH));

        // THEN the new signals JSON went to the explicit UPDATE; no decision, no dispatch hook.
        var jsonCaptor = ArgumentCaptor.forClass(String.class);
        then(repository).should().updateSignals(eq(enrollmentId), jsonCaptor.capture());
        assertThat(jsonCaptor.getValue())
                .as("explicit UPDATE carries the post-transition signal map, discriminator included — "
                        + "an untyped write drops \"kind\" and the row cannot be read back")
                .isEqualTo("{\"GEO_SCORE\":{\"kind\":\"SCORED\",\"riskLevel\":\"HIGH\"},"
                        + "\"FRAUD_CHECK\":{\"kind\":\"PENDING\"}}");
        then(repository).should(never()).completeWithDecision(any(), any(), any(), any(), any());
        simulateCommit();
        then(dispatcher).should(never()).dispatchNow(any());
    }

    @Test
    void recordSignalResult_completesAndDispatchesAfterCommit_whenAllSignalsSettle() {
        // GIVEN a CREDIT_CARD entity with FRAUD already settled OK.
        var enrollmentId = UUID.randomUUID();
        var entity = TestEntityFactory.creditCard(enrollmentId, NOW, TIMEOUT);
        entity.getSignals().put(SignalConfig.FRAUD_CHECK, new SignalState.Checked(CheckOutcome.OK));
        given(repository.findByEnrollmentIdForUpdate(enrollmentId)).willReturn(Optional.of(entity));
        given(repository.completeWithDecision(eq(enrollmentId), anyString(), any(), any(), any())).willReturn(1);

        // WHEN geo settles LOW — both signals now settled, decision fires.
        service.recordSignalResult(enrollmentId, SignalConfig.GEO_SCORE,
                new SignalState.Scored(RiskLevel.LOW));

        // THEN a single combined UPDATE writes signals + decision; no separate updateSignals.
        then(repository).should(never()).updateSignals(any(), any());

        var signalsJsonCaptor = ArgumentCaptor.forClass(String.class);
        var decisionResultCaptor = ArgumentCaptor.forClass(String.class);
        var decisionIdCaptor = ArgumentCaptor.forClass(UUID.class);
        var decidedAtCaptor = ArgumentCaptor.forClass(Instant.class);
        then(repository).should().completeWithDecision(
                eq(enrollmentId),
                signalsJsonCaptor.capture(),
                decisionResultCaptor.capture(),
                decisionIdCaptor.capture(),
                decidedAtCaptor.capture());
        assertThat(signalsJsonCaptor.getValue())
                .as("combined UPDATE carries the post-transition signal map, discriminator included")
                .isEqualTo("{\"GEO_SCORE\":{\"kind\":\"SCORED\",\"riskLevel\":\"LOW\"},"
                        + "\"FRAUD_CHECK\":{\"kind\":\"CHECKED\",\"outcome\":\"OK\"}}");
        assertThat(decisionResultCaptor.getValue()).isEqualTo(DecisionResult.APPROVED.name());
        assertThat(decisionIdCaptor.getValue()).isNotNull();
        assertThat(decidedAtCaptor.getValue()).isEqualTo(FIXED_CLOCK.instant());

        // AND no publish inside the transaction (ADR-17 commit-then-publish) —
        // the eager dispatch fires only on commit.
        then(dispatcher).should(never()).dispatchNow(any());
        simulateCommit();
        then(dispatcher).should().dispatchNow(enrollmentId);
    }

    @Test
    void recordSignalResult_idempotentDiscard_whenSignalAlreadySettled() {
        var enrollmentId = UUID.randomUUID();
        var entity = TestEntityFactory.creditCard(enrollmentId, NOW, TIMEOUT);
        entity.getSignals().put(SignalConfig.GEO_SCORE, new SignalState.Scored(RiskLevel.LOW));
        given(repository.findByEnrollmentIdForUpdate(enrollmentId)).willReturn(Optional.of(entity));

        service.recordSignalResult(enrollmentId, SignalConfig.GEO_SCORE,
                new SignalState.Scored(RiskLevel.HIGH));

        // No write, no decision, no dispatch — silent idempotent return.
        then(repository).should(never()).updateSignals(any(), any());
        then(repository).should(never()).completeWithDecision(any(), any(), any(), any(), any());
        simulateCommit();
        then(dispatcher).should(never()).dispatchNow(any());
    }

    @Test
    void recordSignalResult_throwsUnknownCorrelation_whenRowDoesNotExist() {
        var enrollmentId = UUID.randomUUID();
        given(repository.findByEnrollmentIdForUpdate(enrollmentId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordSignalResult(enrollmentId, SignalConfig.GEO_SCORE,
                new SignalState.Scored(RiskLevel.LOW)))
                .isInstanceOf(UnknownCorrelationException.class)
                .hasMessageContaining(enrollmentId.toString());

        then(repository).should(never()).updateSignals(any(), any());
        then(dispatcher).should(never()).dispatchNow(any());
    }

    @Test
    void recordSignalResult_throwsWhenUpdateSignalsAffectsZeroRows() {
        // Defensive: a row that exists at SELECT FOR UPDATE time but vanishes
        // before the UPDATE is structurally impossible under our row lock —
        // but the row-count assertion turns "impossible drift" into a loud
        // failure rather than a silent missed write (ADR-16 §Write path).
        var enrollmentId = UUID.randomUUID();
        var entity = TestEntityFactory.creditCard(enrollmentId, NOW, TIMEOUT);
        given(repository.findByEnrollmentIdForUpdate(enrollmentId)).willReturn(Optional.of(entity));
        given(repository.updateSignals(eq(enrollmentId), anyString())).willReturn(0);

        assertThatThrownBy(() -> service.recordSignalResult(enrollmentId, SignalConfig.GEO_SCORE,
                new SignalState.Scored(RiskLevel.LOW)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(enrollmentId.toString())
                .hasMessageContaining("0");

        then(repository).should(never()).completeWithDecision(any(), any(), any(), any(), any());
        then(dispatcher).should(never()).dispatchNow(any());
    }

    @Test
    void recordSignalResult_skipsDispatchWhenCompleteWithDecisionAffectsZeroRows() {
        // Edge case: the decision_result IS NULL guard rejected the combined
        // UPDATE, meaning a parallel path already recorded the decision.
        // Under our PESSIMISTIC_WRITE lock this is structurally impossible,
        // but if it does happen we must not register a second dispatch.
        var enrollmentId = UUID.randomUUID();
        var entity = TestEntityFactory.creditCard(enrollmentId, NOW, TIMEOUT);
        entity.getSignals().put(SignalConfig.FRAUD_CHECK, new SignalState.Checked(CheckOutcome.OK));
        given(repository.findByEnrollmentIdForUpdate(enrollmentId)).willReturn(Optional.of(entity));
        given(repository.completeWithDecision(eq(enrollmentId), anyString(), any(), any(), any())).willReturn(0);

        service.recordSignalResult(enrollmentId, SignalConfig.GEO_SCORE,
                new SignalState.Scored(RiskLevel.LOW));

        simulateCommit();
        then(dispatcher).should(never()).dispatchNow(any());
    }

    @Test
    void eagerDispatchFailure_isContainedByTheAfterCommitHook() {
        // The commit is already durable when the hook fires; a dispatch failure must be
        // swallowed (logged) so the inbound message is not nacked for a decision that
        // decided correctly. Re-delivery is the relay's job (ADR-17).
        var enrollmentId = UUID.randomUUID();
        var entity = TestEntityFactory.creditCard(enrollmentId, NOW, TIMEOUT);
        entity.getSignals().put(SignalConfig.FRAUD_CHECK, new SignalState.Checked(CheckOutcome.OK));
        given(repository.findByEnrollmentIdForUpdate(enrollmentId)).willReturn(Optional.of(entity));
        given(repository.completeWithDecision(eq(enrollmentId), anyString(), any(), any(), any())).willReturn(1);
        doThrow(new RuntimeException("broker down")).when(dispatcher).dispatchNow(enrollmentId);

        service.recordSignalResult(enrollmentId, SignalConfig.GEO_SCORE,
                new SignalState.Scored(RiskLevel.LOW));

        assertThatCode(EnrollmentServiceTest::simulateCommit).doesNotThrowAnyException();
        then(dispatcher).should().dispatchNow(enrollmentId);
    }

    @Test
    void recordSignalResult_treatsFailedSignalStateAsAlreadySettled() {
        // A FAILED (timeout) signal is still "not PENDING" — the idempotency
        // guard must skip the late-arriving result rather than overwrite.
        var enrollmentId = UUID.randomUUID();
        var entity = TestEntityFactory.creditCard(enrollmentId, NOW, TIMEOUT);
        entity.getSignals().put(SignalConfig.GEO_SCORE,
                new SignalState.NotExecuted("timeout"));
        given(repository.findByEnrollmentIdForUpdate(enrollmentId)).willReturn(Optional.of(entity));

        service.recordSignalResult(enrollmentId, SignalConfig.GEO_SCORE,
                new SignalState.Scored(RiskLevel.LOW));

        then(repository).should(never()).updateSignals(any(), any());
        simulateCommit();
        then(dispatcher).should(never()).dispatchNow(any());
    }

    /** Reads one settlement counter by its tags. */
    private double settled(SignalConfig signal, String state) {
        return meterRegistry.get(SignalSettlementMetrics.SETTLED_METRIC)
                .tags("signal", signal.name(), "state", state)
                .counter().count();
    }

    @Test
    void settlementCounters_areRegisteredBeforeTheFirstDecision() {
        // The fail-open alert is a ratio; an absent denominator series is not the same as a zero
        // one to increase(), so every series must exist from startup.
        assertThat(settled(SignalConfig.GEO_SCORE, "NOT_EXECUTED")).isZero();
        assertThat(settled(SignalConfig.GEO_SCORE, "SCORED")).isZero();
        assertThat(settled(SignalConfig.FRAUD_CHECK, "NO_RESULT")).isZero();
        assertThat(settled(SignalConfig.FRAUD_CHECK, "CHECKED")).isZero();
    }

    @Test
    void timedOutSignalIsCountedFailOpen_whileTheDecisionItselfLooksHealthy() {
        // GIVEN a CREDIT_CARD row whose deadline passed with GEO_SCORE still pending.
        var enrollmentId = UUID.randomUUID();
        var entity = TestEntityFactory.creditCard(enrollmentId, NOW, TIMEOUT);
        entity.getSignals().put(SignalConfig.FRAUD_CHECK, new SignalState.Checked(CheckOutcome.OK));
        given(repository.claimPendingTimeouts(any(), any())).willReturn(List.of(entity));
        given(repository.completeWithDecision(eq(enrollmentId), anyString(), any(), any(), any())).willReturn(1);

        service.processExpiredTimeouts(TIMEOUT.plusSeconds(1), 10);

        // THEN the decision is APPROVED — nothing in the outcome says a signal is missing.
        var decisionCaptor = ArgumentCaptor.forClass(String.class);
        then(repository).should().completeWithDecision(
                eq(enrollmentId), anyString(), decisionCaptor.capture(), any(), any());
        assertThat(decisionCaptor.getValue()).isEqualTo(DecisionResult.APPROVED.name());

        // ...and the counter is the only place the degradation shows.
        assertThat(settled(SignalConfig.GEO_SCORE, "NOT_EXECUTED")).isEqualTo(1);
        assertThat(settled(SignalConfig.FRAUD_CHECK, "CHECKED")).isEqualTo(1);
        assertThat(settled(SignalConfig.GEO_SCORE, "SCORED")).isZero();
    }

    @Test
    void signalThatRanWithoutAResultIsCountedSeparatelyFromOneThatNeverRan() {
        // NO_RESULT and NOT_EXECUTED are both fail-open but have different causes — the alert
        // splits them by the state tag, so they must not share a series.
        var enrollmentId = UUID.randomUUID();
        var entity = TestEntityFactory.creditCard(enrollmentId, NOW, TIMEOUT);
        entity.getSignals().put(SignalConfig.FRAUD_CHECK, new SignalState.Checked(CheckOutcome.OK));
        given(repository.findByEnrollmentIdForUpdate(enrollmentId)).willReturn(Optional.of(entity));
        given(repository.completeWithDecision(eq(enrollmentId), anyString(), any(), any(), any())).willReturn(1);

        service.recordSignalResult(enrollmentId, SignalConfig.GEO_SCORE,
                new SignalState.NoResult("geocoding unavailable"));

        assertThat(settled(SignalConfig.GEO_SCORE, "NO_RESULT")).isEqualTo(1);
        assertThat(settled(SignalConfig.GEO_SCORE, "NOT_EXECUTED")).isZero();
    }

    @Test
    void nothingIsCountedWhenTheDecisionGuardRejectsTheWrite() {
        // A row another path already completed must not also be counted here.
        var enrollmentId = UUID.randomUUID();
        var entity = TestEntityFactory.creditCard(enrollmentId, NOW, TIMEOUT);
        entity.getSignals().put(SignalConfig.FRAUD_CHECK, new SignalState.Checked(CheckOutcome.OK));
        given(repository.findByEnrollmentIdForUpdate(enrollmentId)).willReturn(Optional.of(entity));
        given(repository.completeWithDecision(eq(enrollmentId), anyString(), any(), any(), any())).willReturn(0);

        service.recordSignalResult(enrollmentId, SignalConfig.GEO_SCORE,
                new SignalState.Scored(RiskLevel.LOW));

        assertThat(settled(SignalConfig.GEO_SCORE, "SCORED")).isZero();
        assertThat(settled(SignalConfig.FRAUD_CHECK, "CHECKED")).isZero();
    }
}
