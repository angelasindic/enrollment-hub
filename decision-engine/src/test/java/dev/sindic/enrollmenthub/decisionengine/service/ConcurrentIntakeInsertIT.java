package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.decisionengine.BaseIntegrationTest;
import dev.sindic.enrollmenthub.decisionengine.domain.Address;
import dev.sindic.enrollmenthub.decisionengine.domain.EnrollmentCommand;
import dev.sindic.enrollmenthub.decisionengine.domain.PaymentType;
import dev.sindic.enrollmenthub.decisionengine.domain.Person;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the intake insert is idempotent under true concurrency.
 *
 * <p>{@code saveIfAbsent} persists via {@code INSERT ... ON CONFLICT
 * (enrollment_id) DO NOTHING} (ADR-13 §Ingress Inversion). The {@code enrollment_id} primary
 * key is the authoritative deduplicator: when N threads race to insert the same
 * id, exactly one wins and the rest are absorbed by the conflict clause — none
 * raises {@code DataIntegrityViolationException}, so none burns the listener's
 * retry budget.
 *
 * <p>Why threads, and what real scenario this models. The same {@code enrollmentId}
 * reaches intake twice in two ways:
 * <ul>
 *   <li><b>Sequential redelivery</b> — a publish fails and the broker redelivers the
 *       same message, processed <i>after</i> the first attempt. No overlap; a single
 *       consumer handles it trivially (the second pass finds the row already present).</li>
 *   <li><b>Simultaneous insert</b> — two inserts of the same id run at the same instant.
 *       A single point-to-point consumer processes messages one at a time and so never
 *       overlaps with itself; real overlap requires ≥2 horizontally-scaled instances both
 *       consuming an at-least-once intake publish. This test forces that overlap with N
 *       threads.</li>
 * </ul>
 */
class ConcurrentIntakeInsertIT extends BaseIntegrationTest {

    private static final int THREADS = 8;
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired EnrollmentCorrelationService correlationService;
    @Autowired EnrollmentRepository repository;

    @Test
    void concurrentInsertsOfSameEnrollmentId_insertOnceWithoutException() throws Exception {
        UUID enrollmentId = UUID.randomUUID();
        Address address = new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE");
        EnrollmentCommand command = new EnrollmentCommand(
                enrollmentId, PaymentType.CREDIT_CARD,
                new Person("Ada", "Lovelace", "ada@example.com", "+49123"), address, address);

        AtomicInteger inserted = new AtomicInteger();   // saveIfAbsent returned true
        AtomicInteger redelivered = new AtomicInteger(); // saveIfAbsent returned false
        AtomicReference<Throwable> failure = new AtomicReference<>();

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            try {
                for (int i = 0; i < THREADS; i++) {
                    pool.submit(() -> {
                        try {
                            if (!start.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("start latch not released within 10s");
                            }
                            if (correlationService.saveIfAbsent(CREATED_AT, EnrollmentMapper.toData(command))) {
                                inserted.incrementAndGet();
                            } else {
                                redelivered.incrementAndGet();
                            }
                        } catch (Throwable ex) {
                            failure.compareAndSet(null, ex);
                        }
                    });
                }
                start.countDown(); // release all threads at once

                pool.shutdown();
                assertThat(pool.awaitTermination(20, TimeUnit.SECONDS))
                        .as("worker threads did not complete in time").isTrue();
            } finally {
                if (!pool.isTerminated()) pool.shutdownNow();
            }
        }

        assertThat(failure.get())
                .as("ON CONFLICT DO NOTHING must absorb the duplicate inserts with no exception")
                .isNull();
        assertThat(inserted.get()).as("exactly one thread inserts the row").isEqualTo(1);
        assertThat(redelivered.get()).as("the rest observe an idempotent redelivery").isEqualTo(THREADS - 1);
        assertThat(repository.findAllById(List.of(enrollmentId))).hasSize(1);
    }
}
