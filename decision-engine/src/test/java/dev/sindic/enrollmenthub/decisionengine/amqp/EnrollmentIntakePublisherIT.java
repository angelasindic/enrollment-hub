package dev.sindic.enrollmenthub.decisionengine.amqp;

import dev.sindic.enrollmenthub.contracts.domain.Address;
import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;
import dev.sindic.enrollmenthub.contracts.domain.PaymentType;
import dev.sindic.enrollmenthub.contracts.domain.Person;
import dev.sindic.enrollmenthub.contracts.events.EnrollmentEvent;
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
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Proves the publisher lands {@link EnrollmentEvent} on the
 * {@code enrollment.intake} exchange (ADR-003 §Layer 1) with the correct
 * per-payment-type routing key, and that an unroutable publish surfaces as
 * {@link AmqpException} via the mandatory + ReturnsCallback path.
 *
 * <p>Uses dedicated capture queues bound to the intake exchange so the
 * test does not interfere with the production {@code enrollment.intake.queue}
 * and its listener.
 */
class EnrollmentIntakePublisherIT extends BaseIntegrationTest {

    private static final String CREDIT_CARD_CAPTURE_QUEUE = "test.intake.capture.credit_card";
    private static final String INVOICE_CAPTURE_QUEUE     = "test.intake.capture.invoice";

    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired RabbitAdmin rabbitAdmin;
    @Autowired @Qualifier("intakeExchange") TopicExchange intakeExchange;
    @Autowired EnrollmentIntakePublisher publisher;
    @Autowired RabbitListenerEndpointRegistry listenerRegistry;

    @BeforeEach
    void declareCaptureQueues() {
        // Stop the production EnrollmentIntakeListener for the duration of this
        // class. The publisher tests only care about the publisher → broker hop;
        // letting the production listener consume these messages causes it to
        // try publishing downstream to enrollment.events, which has no bound
        // queue in this test → NO_ROUTE → @Transactional rollback → infinite
        // redelivery loop that poisons subsequent ITs.
        listenerRegistry.getListenerContainers().forEach(c -> c.stop());

        var creditQueue  = QueueBuilder.nonDurable(CREDIT_CARD_CAPTURE_QUEUE).build();
        var invoiceQueue = QueueBuilder.nonDurable(INVOICE_CAPTURE_QUEUE).build();
        rabbitAdmin.declareQueue(creditQueue);
        rabbitAdmin.declareQueue(invoiceQueue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(creditQueue)
                .to(intakeExchange).with(AmqpConfig.ROUTING_KEY_CREDIT_CARD));
        rabbitAdmin.declareBinding(BindingBuilder.bind(invoiceQueue)
                .to(intakeExchange).with(AmqpConfig.ROUTING_KEY_INVOICE));
    }

    @AfterEach
    void cleanupCaptureQueues() {
        rabbitAdmin.deleteQueue(CREDIT_CARD_CAPTURE_QUEUE);
        rabbitAdmin.deleteQueue(INVOICE_CAPTURE_QUEUE);
        // Purge anything the production listener missed while it was stopped,
        // then restart so subsequent ITs see a healthy listener.
        rabbitAdmin.purgeQueue(AmqpConfig.ENROLLMENT_INTAKE_QUEUE, false);
        listenerRegistry.getListenerContainers().forEach(c -> c.start());
    }

    @Test
    void publishesCreditCardToCreditCardRoutingKey() {
        var enrollmentId = UUID.randomUUID();
        var event = new EnrollmentEvent(Instant.now(), enrollmentData(enrollmentId, PaymentType.CREDIT_CARD));

        publisher.publish(event);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var received = (EnrollmentEvent) rabbitTemplate.receiveAndConvert(
                    CREDIT_CARD_CAPTURE_QUEUE, 100);
            assertThat(received).isNotNull();
            assertThat(received.enrollmentId()).isEqualTo(enrollmentId.toString());
            assertThat(received.enrollmentData().paymentType()).isEqualTo(PaymentType.CREDIT_CARD);
        });
        assertThat(rabbitTemplate.receive(INVOICE_CAPTURE_QUEUE, 100))
                .as("CREDIT_CARD routing key must not land on the INVOICE binding")
                .isNull();
    }

    @Test
    void publishesInvoiceToInvoiceRoutingKey() {
        var enrollmentId = UUID.randomUUID();
        var event = new EnrollmentEvent(Instant.now(), enrollmentData(enrollmentId, PaymentType.INVOICE));

        publisher.publish(event);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var received = (EnrollmentEvent) rabbitTemplate.receiveAndConvert(
                    INVOICE_CAPTURE_QUEUE, 100);
            assertThat(received).isNotNull();
            assertThat(received.enrollmentData().paymentType()).isEqualTo(PaymentType.INVOICE);
        });
        assertThat(rabbitTemplate.receive(CREDIT_CARD_CAPTURE_QUEUE, 100)).isNull();
    }

    @Test
    void unroutablePublishThrowsAmqpException() {
        // Remove the binding (but not the queue itself) so the intake exchange
        // has no queue listening for our routing key. mandatory=true +
        // ReturnsCallback then surfaces the publish failure. Deleting the
        // production queue itself is more destructive: it kicks the
        // SimpleMessageListenerContainer's consumer into recovery mode and
        // pollutes the next IT.
        var productionBinding = BindingBuilder.bind(
                        QueueBuilder.durable(AmqpConfig.ENROLLMENT_INTAKE_QUEUE).build())
                .to(intakeExchange)
                .with("enrollment.created.*");
        rabbitAdmin.removeBinding(productionBinding);
        rabbitAdmin.deleteQueue(CREDIT_CARD_CAPTURE_QUEUE);
        rabbitAdmin.deleteQueue(INVOICE_CAPTURE_QUEUE);

        var event = new EnrollmentEvent(Instant.now(),
                enrollmentData(UUID.randomUUID(), PaymentType.CREDIT_CARD));

        try {
            assertThatThrownBy(() -> publisher.publish(event))
                    .isInstanceOf(AmqpException.class)
                    .hasMessageContaining("unroutable")
                    .hasMessageContaining(AmqpConfig.ENROLLMENT_INTAKE_EXCHANGE);
        } finally {
            rabbitAdmin.declareBinding(productionBinding);
        }
    }

    private static EnrollmentData enrollmentData(UUID enrollmentId, PaymentType paymentType) {
        return new EnrollmentData(
                enrollmentId,
                paymentType,
                new Person("Ada", "Lovelace", "ada@example.com", "+49123"),
                new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE"),
                new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE"));
    }
}
