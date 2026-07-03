package dev.sindic.enrollmenthub.decisionengine.service;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the two-phase sweep orchestration. Each phase keeps claiming while a full batch
 * comes back and stops on the first short batch, so a backlog clears in one wake-up. Phase order
 * (timeouts before dispatch) and phase isolation (one failing phase must not skip the other) are
 * the behaviours this job owns; the phase logic itself is tested in {@code EnrollmentSweepIT}.
 */
class EnrollmentSweepJobTest {

    private static final Instant NOW = Instant.parse("2026-06-08T00:00:00Z");
    private static final int BATCH = 100;
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private EnrollmentSweepJob job(EnrollmentService service, DecisionDispatcher dispatcher) {
        return new EnrollmentSweepJob(service, dispatcher, clock,
                new SweepProperties(Duration.ofSeconds(10), BATCH));
    }

    @Test
    void runsTimeoutPhaseBeforeDispatchPhase() {
        var service = mock(EnrollmentService.class);
        var dispatcher = mock(DecisionDispatcher.class);
        when(service.processExpiredTimeouts(eq(NOW), eq(BATCH))).thenReturn(0);
        when(dispatcher.dispatchPending(eq(BATCH))).thenReturn(0);

        job(service, dispatcher).sweep();

        InOrder inOrder = inOrder(service, dispatcher);
        inOrder.verify(service).processExpiredTimeouts(NOW, BATCH);
        inOrder.verify(dispatcher).dispatchPending(BATCH);
    }

    @Test
    void drainsEachPhaseUntilBatchNotFull() {
        var service = mock(EnrollmentService.class);
        var dispatcher = mock(DecisionDispatcher.class);
        // Two full batches then a short one — each loop must run a third time to observe the short batch.
        when(service.processExpiredTimeouts(eq(NOW), eq(BATCH))).thenReturn(BATCH, BATCH, 37);
        when(dispatcher.dispatchPending(eq(BATCH))).thenReturn(BATCH, BATCH, 12);

        job(service, dispatcher).sweep();

        verify(service, times(3)).processExpiredTimeouts(NOW, BATCH);
        verify(dispatcher, times(3)).dispatchPending(BATCH);
    }

    @Test
    void singlePassPerPhase_whenFirstBatchNotFull() {
        var service = mock(EnrollmentService.class);
        var dispatcher = mock(DecisionDispatcher.class);
        when(service.processExpiredTimeouts(eq(NOW), eq(BATCH))).thenReturn(0);
        when(dispatcher.dispatchPending(eq(BATCH))).thenReturn(0);

        job(service, dispatcher).sweep();

        verify(service, times(1)).processExpiredTimeouts(NOW, BATCH);
        verify(dispatcher, times(1)).dispatchPending(BATCH);
    }

    @Test
    void timeoutPhaseFailure_doesNotSkipDispatchPhase() {
        var service = mock(EnrollmentService.class);
        var dispatcher = mock(DecisionDispatcher.class);
        when(service.processExpiredTimeouts(eq(NOW), eq(BATCH)))
                .thenThrow(new RuntimeException("db hiccup"));
        when(dispatcher.dispatchPending(eq(BATCH))).thenReturn(0);

        job(service, dispatcher).sweep();

        // The sweep must not propagate; the dispatch backstop still runs this tick.
        verify(dispatcher, times(1)).dispatchPending(BATCH);
    }

    @Test
    void dispatchPhaseFailure_isContained() {
        var service = mock(EnrollmentService.class);
        var dispatcher = mock(DecisionDispatcher.class);
        when(service.processExpiredTimeouts(eq(NOW), eq(BATCH))).thenReturn(0);
        when(dispatcher.dispatchPending(eq(BATCH))).thenThrow(new RuntimeException("broker down"));

        // A broker outage in the dispatch phase must not escape the scheduled method.
        job(service, dispatcher).sweep();

        verify(service, times(1)).processExpiredTimeouts(NOW, BATCH);
    }
}
