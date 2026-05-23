package dev.sindic.enrollmenthub.decisionengine.amqp;

import dev.sindic.enrollmenthub.contracts.domain.Address;
import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;
import dev.sindic.enrollmenthub.contracts.domain.PaymentType;
import dev.sindic.enrollmenthub.contracts.domain.Person;
import dev.sindic.enrollmenthub.contracts.events.EnrollmentEvent;
import dev.sindic.enrollmenthub.decisionengine.BaseIntegrationTest;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Proves the broker-backed durability property of the C1 intake design:
 * a redelivered intake message must result in <b>at most one</b> correlation row
 * and the downstream event must continue to be observable on the events exchange.
 *
 * <p>Simulates the "crash between save and ACK" scenario by publishing the same
 * intake message twice directly to the intake exchange (bypassing the publisher
 * confirm so we don't change the EnrollmentIntakePublisher's per-publish
 * correlation id; the broker treats each as a fresh delivery). The
 * {@code existsById} idempotency gate in
 * {@link dev.sindic.enrollmenthub.decisionengine.service.EnrollmentIntakeService#processEnrollment}
 * is what makes the second delivery a no-op rather than a PK-violation /
 * eventual DLQ.
 *
 * <p>Under at-least-once delivery with an idempotent receiver (ADR-003
 * §"Delivery Guarantees"), the events queue may receive the {@code EnrollmentEvent}
 * one or two times depending on which side of the listener's transaction the
 * second delivery races into. Either outcome is correct; the test asserts only
 * that <i>at least one</i> event is observed.
 */
class EnrollmentIntakeRedeliveryIT extends BaseIntegrationTest {

    private static final String EVENTS_CAPTURE_QUEUE = "test.intake-redelivery.events.credit_card";

    @Autowired EnrollmentRepository repository;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired RabbitAdmin rabbitAdmin;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("enrollmentExchange") TopicExchange enrollmentExchange;
    @Autowired @Qualifier("intakeExchange") TopicExchange intakeExchange;

    @BeforeEach
    void declareCaptureQueue() {
        // Defensive re-declaration: a prior IT (e.g. unroutable-publish tests in
        // EnrollmentIntakePublisherIT) may have removed the production intake
        // binding. Spring's RabbitAdmin auto-declares on connection events, but
        // there's no connection event between tests, so the binding can stay
        // absent. Redeclaring here is idempotent and guarantees the listener
        // actually receives the messages this test publishes.
        rabbitAdmin.declareBinding(BindingBuilder.bind(
                        QueueBuilder.durable(AmqpConfig.ENROLLMENT_INTAKE_QUEUE).build())
                .to(intakeExchange).with("enrollment.created.*"));

        var queue = QueueBuilder.nonDurable(EVENTS_CAPTURE_QUEUE).build();
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue)
                .to(enrollmentExchange).with(AmqpConfig.ROUTING_KEY_CREDIT_CARD));
    }

    @AfterEach
    void cleanupCaptureQueue() {
        rabbitAdmin.deleteQueue(EVENTS_CAPTURE_QUEUE);
    }

    @Test
    void redeliveredIntakeMessage_producesExactlyOneRow() {
        var enrollmentId = UUID.randomUUID();
        var event = new EnrollmentEvent(
                Instant.parse("2026-05-23T10:00:00Z"),
                enrollmentData(enrollmentId));

        // Two independent publishes of the same payload — broker treats each as
        // a fresh delivery to the intake queue. From the listener's perspective
        // this is indistinguishable from a single message redelivered after a
        // crash-between-save-and-ACK.
        rabbitTemplate.convertAndSend(
                AmqpConfig.ENROLLMENT_INTAKE_EXCHANGE,
                AmqpConfig.ROUTING_KEY_CREDIT_CARD,
                event);
        rabbitTemplate.convertAndSend(
                AmqpConfig.ENROLLMENT_INTAKE_EXCHANGE,
                AmqpConfig.ROUTING_KEY_CREDIT_CARD,
                event);

        // Wait for the row to appear AND remain a single row through the
        // listener's second-delivery processing. Direct JdbcTemplate read so
        // we don't go through Hibernate's persistence context (which can hold
        // a stale snapshot across the listener's commit boundary).
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Integer rowCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM enrollment_hub.enrollments WHERE enrollment_id = ?",
                    Integer.class, enrollmentId);
            assertThat(rowCount)
                    .as("listener must persist exactly one row for the enrollmentId, " +
                            "regardless of how many times the intake message was delivered")
                    .isEqualTo(1);
        });

        // Give the listener a window to consume the second delivery (which
        // would have raced through the idempotency gate). Then re-assert
        // exactly one row — the PK guarantees this if the gate works.
        await().pollDelay(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            Integer rowCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM enrollment_hub.enrollments WHERE enrollment_id = ?",
                    Integer.class, enrollmentId);
            assertThat(rowCount).isEqualTo(1);
        });

        // The events exchange saw the EnrollmentEvent at least once. At-least-once
        // delivery: the count can be 1 (idempotency gate caught the second delivery
        // post-commit of the first) or 2 (second delivery raced ahead of the first
        // commit, both attempts saved and published; PK would prevent the second
        // save though). Asserting "at least one" keeps the test deterministic
        // without over-specifying the timing.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            EnrollmentEvent received = (EnrollmentEvent) rabbitTemplate.receiveAndConvert(
                    EVENTS_CAPTURE_QUEUE, 100);
            assertThat(received)
                    .as("at-least-once delivery: downstream must see the event")
                    .isNotNull();
            assertThat(received.enrollmentId()).isEqualTo(enrollmentId.toString());
        });
    }

    private static EnrollmentData enrollmentData(UUID enrollmentId) {
        return new EnrollmentData(
                enrollmentId,
                PaymentType.CREDIT_CARD,
                new Person("Ada", "Lovelace", "ada@example.com", "+49123"),
                new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE"),
                new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE"));
    }
}
