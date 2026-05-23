package dev.sindic.enrollmenthub.decisionengine.amqp;

import dev.sindic.enrollmenthub.contracts.domain.Address;
import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;
import dev.sindic.enrollmenthub.contracts.domain.PaymentType;
import dev.sindic.enrollmenthub.contracts.domain.Person;
import dev.sindic.enrollmenthub.contracts.events.DecisionResult;
import dev.sindic.enrollmenthub.contracts.events.EnrollmentDecisionEvent;
import dev.sindic.enrollmenthub.contracts.events.EnrollmentSignal;
import dev.sindic.enrollmenthub.contracts.events.RiskLevel;
import dev.sindic.enrollmenthub.contracts.events.SignalOutcome;
import dev.sindic.enrollmenthub.decisionengine.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Proves that {@link EnrollmentDecisionPublisher} publishes to
 * {@code enrollment.decisions} (ADR-003 §Layer 3) and not to
 * {@code enrollment.events} (Layer 2). Capture queue stands in for the
 * account-service consumer queue (the account-service owns its real binding —
 * out of scope for this module).
 */
class EnrollmentDecisionPublisherIT extends BaseIntegrationTest {

    private static final String DECISION_CAPTURE_QUEUE = "test.capture.decisions";
    private static final String EVENTS_CAPTURE_QUEUE   = "test.capture.events_decision_negative";

    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired RabbitAdmin rabbitAdmin;
    @Autowired @Qualifier("enrollmentDecisionsExchange") TopicExchange enrollmentDecisionsExchange;
    @Autowired @Qualifier("enrollmentExchange") TopicExchange enrollmentExchange;
    @Autowired EnrollmentDecisionPublisher publisher;

    @BeforeEach
    void declareCaptureQueues() {
        var decisionQueue = QueueBuilder.nonDurable(DECISION_CAPTURE_QUEUE).build();
        rabbitAdmin.declareQueue(decisionQueue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(decisionQueue)
                .to(enrollmentDecisionsExchange)
                .with(AmqpConfig.DECISION_ROUTING_KEY));

        // Negative-control queue: bound to the OLD exchange (events) with the
        // same routing key. Should never receive a decision event after M3.
        var eventsQueue = QueueBuilder.nonDurable(EVENTS_CAPTURE_QUEUE).build();
        rabbitAdmin.declareQueue(eventsQueue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(eventsQueue)
                .to(enrollmentExchange)
                .with(AmqpConfig.DECISION_ROUTING_KEY));
    }

    @AfterEach
    void cleanupCaptureQueues() {
        rabbitAdmin.deleteQueue(DECISION_CAPTURE_QUEUE);
        rabbitAdmin.deleteQueue(EVENTS_CAPTURE_QUEUE);
    }

    @Test
    void publishesDecisionEventToDecisionsExchange() {
        var event = sampleDecisionEvent();

        publisher.publish(event);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var received = (EnrollmentDecisionEvent) rabbitTemplate.receiveAndConvert(
                    DECISION_CAPTURE_QUEUE, 100);
            assertThat(received).isNotNull();
            assertThat(received.decisionId()).isEqualTo(event.decisionId());
            assertThat(received.decisionResult()).isEqualTo(DecisionResult.APPROVED);
        });
    }

    @Test
    void doesNotPublishDecisionEventToEventsExchange() {
        // The negative-control queue is bound to enrollment.events with the
        // same routing key. If M3 regresses (publisher reverts to EXCHANGE),
        // this assertion will flip from null to a delivered message.
        publisher.publish(sampleDecisionEvent());

        await().pollDelay(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(
                        rabbitTemplate.receive(EVENTS_CAPTURE_QUEUE, 100))
                        .as("decision event must not reach the events exchange after M3")
                        .isNull());
    }

    @Test
    void unroutablePublishThrowsAmqpException() {
        // If the decisions exchange has no queue bound for the routing key, the
        // mandatory=true template + ReturnsCallback path turns the publish into
        // a thrown AmqpException. This is the "rollout caveat" guard documented
        // on the publisher class — merging this change without the downstream
        // account-service binding would surface here in production.
        rabbitAdmin.deleteQueue(DECISION_CAPTURE_QUEUE);

        assertThatThrownBy(() -> publisher.publish(sampleDecisionEvent()))
                .isInstanceOf(AmqpException.class)
                .hasMessageContaining("unroutable")
                .hasMessageContaining(AmqpConfig.DECISION_EXCHANGE);
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private static EnrollmentDecisionEvent sampleDecisionEvent() {
        var person  = new Person("Ada", "Lovelace", "ada@example.com", "+49123");
        var address = new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE");
        var data    = new EnrollmentData(UUID.randomUUID(), PaymentType.CREDIT_CARD, person, address, address);

        return new EnrollmentDecisionEvent(
                UUID.randomUUID(),
                data,
                DecisionResult.APPROVED,
                Map.of(
                        "GEO_SCORE",   new EnrollmentSignal(null, RiskLevel.LOW, null),
                        "FRAUD_CHECK", new EnrollmentSignal(SignalOutcome.OK, null, null)),
                Instant.parse("2026-05-21T10:00:00Z"));
    }
}
