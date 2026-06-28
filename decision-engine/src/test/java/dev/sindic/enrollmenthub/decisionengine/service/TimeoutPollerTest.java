package dev.sindic.enrollmenthub.decisionengine.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the drain loop. The poller keeps claiming while a full batch comes back and stops
 * on the first short batch, so a backlog is cleared in one wake-up without one batch per interval.
 */
class TimeoutPollerTest {

    private static final Instant NOW = Instant.parse("2026-06-08T00:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void drainsUntilBatchNotFull() {
        var service = mock(EnrollmentService.class);
        // Two full batches then a short one — the loop must run a third time to observe the short batch.
        when(service.processExpiredTimeouts(eq(NOW), eq(100))).thenReturn(100, 100, 37);

        var poller = new TimeoutPoller(service, clock, new TimeoutPollerProperties(Duration.ofSeconds(10), 100));
        poller.pollExpiredTimeouts();

        verify(service, times(3)).processExpiredTimeouts(NOW, 100);
    }

    @Test
    void singlePass_whenFirstBatchNotFull() {
        var service = mock(EnrollmentService.class);
        when(service.processExpiredTimeouts(eq(NOW), eq(100))).thenReturn(0);

        var poller = new TimeoutPoller(service, clock, new TimeoutPollerProperties(Duration.ofSeconds(10), 100));
        poller.pollExpiredTimeouts();

        verify(service, times(1)).processExpiredTimeouts(NOW, 100);
    }
}
