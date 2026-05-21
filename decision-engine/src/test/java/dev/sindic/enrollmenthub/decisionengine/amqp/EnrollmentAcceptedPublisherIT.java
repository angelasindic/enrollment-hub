package dev.sindic.enrollmenthub.decisionengine.amqp;

import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;
import dev.sindic.enrollmenthub.contracts.domain.Address;
import dev.sindic.enrollmenthub.contracts.domain.PaymentType;
import dev.sindic.enrollmenthub.contracts.domain.Person;
import dev.sindic.enrollmenthub.contracts.events.EnrollmentAccepted;
import dev.sindic.enrollmenthub.decisionengine.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Proves that the decision-engine can publish {@link EnrollmentAccepted} to the
 * expected route for both payment types. Capture queues stand in for the real
 * worker queues (ADR-003 — consumer-owned bindings; the decision-engine knows nothing
 * about the real queues, only the exchange + routing keys).
 */
class EnrollmentAcceptedPublisherIT extends BaseIntegrationTest {

    private static final String CREDIT_CARD_CAPTURE_QUEUE = "test.capture.credit_card";
    private static final String INVOICE_CAPTURE_QUEUE     = "test.capture.invoice";

    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired RabbitAdmin rabbitAdmin;
    @Autowired TopicExchange enrollmentExchange;
    @Autowired EnrollmentAcceptedPublisher publisher;

    @BeforeEach
    void declareCaptureQueues() {
        var creditCardQueue = QueueBuilder.nonDurable(CREDIT_CARD_CAPTURE_QUEUE).build();
        var invoiceQueue    = QueueBuilder.nonDurable(INVOICE_CAPTURE_QUEUE).build();
        rabbitAdmin.declareQueue(creditCardQueue);
        rabbitAdmin.declareQueue(invoiceQueue);
        rabbitAdmin.declareBinding(
                BindingBuilder.bind(creditCardQueue).to(enrollmentExchange).with(AmqpConfig.ROUTING_KEY_CREDIT_CARD));
        rabbitAdmin.declareBinding(
                BindingBuilder.bind(invoiceQueue).to(enrollmentExchange).with(AmqpConfig.ROUTING_KEY_INVOICE));
    }

    @AfterEach
    void cleanupCaptureQueues() {
        rabbitAdmin.deleteQueue(CREDIT_CARD_CAPTURE_QUEUE);
        rabbitAdmin.deleteQueue(INVOICE_CAPTURE_QUEUE);
    }

    @Test
    void publishesCreditCardEventToCreditCardRoutingKey() {
        var requestId = UUID.randomUUID();

        publisher.publish(new EnrollmentAccepted(requestId, enrollmentData(PaymentType.CREDIT_CARD)));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var received = (EnrollmentAccepted) rabbitTemplate.receiveAndConvert(
                    CREDIT_CARD_CAPTURE_QUEUE, 100);
            assertThat(received).isNotNull();
            assertThat(received.requestId()).isEqualTo(requestId);
            assertThat(received.enrollmentData().paymentType()).isEqualTo(PaymentType.CREDIT_CARD);
        });
        assertThat(rabbitTemplate.receive(INVOICE_CAPTURE_QUEUE, 100)).isNull();
    }

    @Test
    void publishesInvoiceEventToInvoiceRoutingKey() {
        var requestId = UUID.randomUUID();

        publisher.publish(new EnrollmentAccepted(requestId, enrollmentData(PaymentType.INVOICE)));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var received = (EnrollmentAccepted) rabbitTemplate.receiveAndConvert(
                    INVOICE_CAPTURE_QUEUE, 100);
            assertThat(received).isNotNull();
            assertThat(received.requestId()).isEqualTo(requestId);
            assertThat(received.enrollmentData().paymentType()).isEqualTo(PaymentType.INVOICE);
        });
        assertThat(rabbitTemplate.receive(CREDIT_CARD_CAPTURE_QUEUE, 100)).isNull();
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private static EnrollmentData enrollmentData(PaymentType paymentType) {
        var person  = new Person("Ada", "Lovelace", "ada@example.com", "+49123");
        var address = new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE");
        return new EnrollmentData(paymentType, person, address, address);
    }
}
