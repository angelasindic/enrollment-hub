package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.contracts.events.EnrollmentDecisionEvent;
import dev.sindic.enrollmenthub.decisionengine.amqp.EnrollmentDecisionPublisher;
import dev.sindic.enrollmenthub.decisionengine.domain.DecisionResult;
import dev.sindic.enrollmenthub.decisionengine.domain.RiskLevel;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalConfig;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalOutcome;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalState;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentEntity;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class DecisionDispatcherTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-04-13T12:01:00Z"), ZoneOffset.UTC);
    private static final Instant DECIDED_AT = Instant.parse("2026-04-13T12:00:42Z");

    @Mock EnrollmentRepository repository;
    @Mock EnrollmentDecisionPublisher publisher;

    DecisionDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new DecisionDispatcher(repository, publisher,
                JsonMapper.builder().findAndAddModules().build(), FIXED_CLOCK);
    }

    @Test
    void dispatchNow_publishesThePersistedDecision_andStampsAfterwards() {
        var enrollmentId = UUID.randomUUID();
        var decisionId = UUID.randomUUID();
        var entity = decidedEntity(enrollmentId, decisionId);
        given(repository.findById(enrollmentId)).willReturn(Optional.of(entity));
        given(repository.markDispatched(eq(enrollmentId), any())).willReturn(1);

        dispatcher.dispatchNow(enrollmentId);

        // The event replays the persisted decision — same decisionId, no recompute (ADR-17).
        var eventCaptor = ArgumentCaptor.forClass(EnrollmentDecisionEvent.class);
        then(publisher).should().publish(eventCaptor.capture());
        var event = eventCaptor.getValue();
        assertThat(event.decisionId()).isEqualTo(decisionId);
        assertThat(event.decidedAt()).isEqualTo(DECIDED_AT);
        assertThat(event.decisionResult())
                .isEqualTo(dev.sindic.enrollmenthub.contracts.events.DecisionResult.APPROVED);
        assertThat(event.signals().get("GEO_SCORE").riskLevel())
                .isEqualTo(dev.sindic.enrollmenthub.contracts.events.RiskLevel.LOW);
        assertThat(event.signals().get("FRAUD_CHECK").outcome())
                .isEqualTo(dev.sindic.enrollmenthub.contracts.events.SignalOutcome.OK);

        then(repository).should().markDispatched(enrollmentId, FIXED_CLOCK.instant());
    }

    @Test
    void dispatchNow_skips_whenRelayAlreadyStampedTheRow() {
        var enrollmentId = UUID.randomUUID();
        var entity = decidedEntity(enrollmentId, UUID.randomUUID());
        given(entity.getDispatchedAt()).willReturn(Instant.parse("2026-04-13T12:00:50Z"));
        given(repository.findById(enrollmentId)).willReturn(Optional.of(entity));

        dispatcher.dispatchNow(enrollmentId);

        then(publisher).should(never()).publish(any());
        then(repository).should(never()).markDispatched(any(), any());
    }

    @Test
    void dispatchNow_skipsQuietly_whenRowIsMissingOrUndecided() {
        var missing = UUID.randomUUID();
        given(repository.findById(missing)).willReturn(Optional.empty());
        dispatcher.dispatchNow(missing);

        var undecided = UUID.randomUUID();
        var entity = mock(EnrollmentEntity.class);
        given(entity.getDecisionResult()).willReturn(null);
        given(repository.findById(undecided)).willReturn(Optional.of(entity));
        dispatcher.dispatchNow(undecided);

        then(publisher).should(never()).publish(any());
    }

    @Test
    void dispatchNow_toleratesLosingTheStampRace() {
        // Publish succeeded but another trigger stamped first: markDispatched returns 0.
        // The duplicate is byte-identical (same decisionId) — logged, never thrown.
        var enrollmentId = UUID.randomUUID();
        var entity = decidedEntity(enrollmentId, UUID.randomUUID());
        given(repository.findById(enrollmentId)).willReturn(Optional.of(entity));
        given(repository.markDispatched(eq(enrollmentId), any())).willReturn(0);

        dispatcher.dispatchNow(enrollmentId);

        then(publisher).should().publish(any());
    }

    @Test
    void dispatchPending_dispatchesEveryClaimedRow_andReportsTheCount() {
        var first = decidedEntity(UUID.randomUUID(), UUID.randomUUID());
        var second = decidedEntity(UUID.randomUUID(), UUID.randomUUID());
        given(repository.claimUndispatched(any(Pageable.class))).willReturn(List.of(first, second));
        given(repository.markDispatched(any(), any())).willReturn(1);

        int dispatched = dispatcher.dispatchPending(50);

        assertThat(dispatched).isEqualTo(2);
        then(publisher).should().publish(argThatDecisionId(first.getDecisionId()));
        then(publisher).should().publish(argThatDecisionId(second.getDecisionId()));
    }

    @Test
    void dispatchPending_emptyClaim_publishesNothing() {
        given(repository.claimUndispatched(any(Pageable.class))).willReturn(List.of());

        assertThat(dispatcher.dispatchPending(50)).isZero();

        then(publisher).should(never()).publish(any());
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    /**
     * The entity's decision columns are written only by SQL UPDATEs (read-only projection),
     * so a decided row cannot be constructed through the entity API — mock the getters the
     * dispatcher reads instead.
     */
    private static EnrollmentEntity decidedEntity(UUID enrollmentId, UUID decisionId) {
        var signals = new EnumMap<SignalConfig, SignalState>(SignalConfig.class);
        signals.put(SignalConfig.GEO_SCORE, SignalState.settled(RiskLevel.LOW));
        signals.put(SignalConfig.FRAUD_CHECK, SignalState.settled(SignalOutcome.OK));

        var entity = mock(EnrollmentEntity.class);
        lenient().when(entity.getEnrollmentId()).thenReturn(enrollmentId);
        lenient().when(entity.getDecisionResult()).thenReturn(DecisionResult.APPROVED);
        lenient().when(entity.getDecisionId()).thenReturn(decisionId);
        lenient().when(entity.getDecidedAt()).thenReturn(DECIDED_AT);
        lenient().when(entity.getDispatchedAt()).thenReturn(null);
        lenient().when(entity.getSignals()).thenReturn(signals);
        lenient().when(entity.getOriginalRequest()).thenReturn(originalRequestJson(enrollmentId));
        return entity;
    }

    private static String originalRequestJson(UUID enrollmentId) {
        return ("{\"enrollmentId\":\"%s\",\"paymentType\":\"CREDIT_CARD\","
                + "\"person\":{\"emailAddress\":\"test@example.com\"},"
                + "\"shippingAddress\":{\"countryCode\":\"DE\"},"
                + "\"billingAddress\":{\"countryCode\":\"DE\"}}").formatted(enrollmentId);
    }

    private static EnrollmentDecisionEvent argThatDecisionId(UUID decisionId) {
        return org.mockito.ArgumentMatchers.argThat(e -> e.decisionId().equals(decisionId));
    }
}
