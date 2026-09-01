package dev.sindic.enrollmenthub.decisionengine.persistence;

import dev.sindic.enrollmenthub.decisionengine.BaseIntegrationTest;
import dev.sindic.enrollmenthub.decisionengine.domain.*;
import dev.sindic.enrollmenthub.decisionengine.TestEntityFactory;
import dev.sindic.enrollmenthub.decisionengine.service.SignalMapJson;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.EnumMap;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that {@code SELECT FOR UPDATE} (pessimistic write lock) serializes
 * concurrent result handlers on the same correlation row. Without the lock,
 * both handlers could read stale state and neither would detect completion.
 *
 * <p>Each handler follows the production flow per ADR-16 §Write path:
 * lock with {@code findByEnrollmentIdForUpdate}, record the result in a copy of the
 * signal map, persist via {@code repository.updateSignals}, and read the completion
 * predicate off the just-computed map (not off the stale in-memory entity).
 */
class ConcurrentCompletionIT extends BaseIntegrationTest {

    @Autowired EnrollmentRepository repository;
    @Autowired TransactionTemplate txTemplate;
    @Autowired JsonMapper jsonMapper;

    @Test
    void concurrentHandlers_serializedByPessimisticLock_exactlyOneSeesCompletion() throws Exception {
        var enrollmentId = UUID.randomUUID();
        txTemplate.executeWithoutResult(status -> repository.saveAndFlush(
                TestEntityFactory.creditCard(enrollmentId, Instant.now(), Instant.now().plusSeconds(60))));

        var barrier = new CyclicBarrier(2);

        var geoSawComplete   = new AtomicBoolean(false);
        var fraudSawComplete = new AtomicBoolean(false);
        var geoError         = new AtomicReference<Throwable>();
        var fraudError       = new AtomicReference<Throwable>();

        var geoThread = Thread.ofVirtual().name("geo-handler").start(() -> {
            try {
                barrier.await();
                txTemplate.executeWithoutResult(status -> {
                    var entity = repository.findByEnrollmentIdForUpdate(enrollmentId).orElseThrow();
                    sleep(200);
                    var updated = new EnumMap<>(entity.getSignals());
                    updated.put(SignalConfig.GEO_SCORE, new SignalState.Scored(RiskLevel.HIGH));
                    repository.updateSignals(enrollmentId, SignalMapJson.write(jsonMapper, updated));
                    geoSawComplete.set(SignalConfig.allSettled(updated));
                });
            } catch (Throwable t) {
                geoError.set(t);
            }
        });

        var fraudThread = Thread.ofVirtual().name("fraud-handler").start(() -> {
            try {
                barrier.await();
                txTemplate.executeWithoutResult(status -> {
                    var entity = repository.findByEnrollmentIdForUpdate(enrollmentId).orElseThrow();
                    sleep(200);
                    var updated = new EnumMap<>(entity.getSignals());
                    updated.put(SignalConfig.FRAUD_CHECK, new SignalState.Checked(SignalOutcome.OK));
                    repository.updateSignals(enrollmentId, SignalMapJson.write(jsonMapper, updated));
                    fraudSawComplete.set(SignalConfig.allSettled(updated));
                });
            } catch (Throwable t) {
                fraudError.set(t);
            }
        });

        geoThread.join(10_000);
        fraudThread.join(10_000);

        assertThat(geoError.get()).isNull();
        assertThat(fraudError.get()).isNull();

        var final_ = txTemplate.execute(status -> repository.findById(enrollmentId).orElseThrow());
        assertThat(final_.getSignals().get(SignalConfig.GEO_SCORE))
                .isEqualTo(new SignalState.Scored(RiskLevel.HIGH));
        assertThat(final_.getSignals().get(SignalConfig.FRAUD_CHECK))
                .isEqualTo(new SignalState.Checked(SignalOutcome.OK));
        assertThat(SignalConfig.allSettled(final_.getSignals())).isTrue();

        assertThat(geoSawComplete.get() ^ fraudSawComplete.get())
                .as("Exactly one handler should observe completion, not both and not neither")
                .isTrue();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
