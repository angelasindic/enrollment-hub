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
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.QueueBuilder;
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
 * Proves that {@link EnrollmentIntakePublisher} lands every {@link EnrollmentEvent}
 * on the {@code enrollment.intake} direct exchange using the fixed routing key
 * {@link AmqpConfig#ENROLLMENT_INTAKE_ROUTING_KEY}, regardless of payment type.
 *
 * <p>Uses a single capture queue bound to the fixed key so the test does not
 * interfere with the production {@code enrollment.intake.queue} and its listener.
 */
class EnrollmentIntakePublisherIT extends BaseIntegrationTest {

    private static final String INTAKE_CAPTURE_QUEUE = "test.intake.capture";

    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired RabbitAdmin rabbitAdmin;
    @Autowired @Qualifier("intakeExchange") DirectExchange intakeExchange;
    @Autowired EnrollmentIntakePublisher publisher;
    @Autowired RabbitListenerEndpointRegistry listenerRegistry;

    @BeforeEach
    void setUp() {
        // Stop the production EnrollmentIntakeListener for the duration of this
        // class. The publisher tests only care about the publisher → broker hop;
        // letting the production listener consume these messages causes it to
        // try publishing downstream to enrollment.events, which has no bound
        // queue in this test → NO_ROUTE → @Transactional rollback → infinite
        // redelivery loop that poisons subsequent ITs.
        listenerRegistry.getListenerContainers().forEach(c -> c.stop());

        var captureQueue = QueueBuilder.nonDurable(INTAKE_CAPTURE_QUEUE).build();
        rabbitAdmin.declareQueue(captureQueue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(captureQueue)
                .to(intakeExchange).with(AmqpConfig.ENROLLMENT_INTAKE_ROUTING_KEY));
    }

    @AfterEach
    void tearDown() {
        rabbitAdmin.deleteQueue(INTAKE_CAPTURE_QUEUE);
        rabbitAdmin.purgeQueue(AmqpConfig.ENROLLMENT_INTAKE_QUEUE, false);
        listenerRegistry.getListenerContainers().forEach(c -> c.start());
    }

    @Test
    void publishCreditCardEnrollment_landsOnIntakeQueue() {
        var enrollmentId = UUID.randomUUID();
        var event = new EnrollmentEvent(Instant.now(), enrollmentData(enrollmentId, PaymentType.CREDIT_CARD));

        publisher.publish(event);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var received = (EnrollmentEvent) rabbitTemplate.receiveAndConvert(INTAKE_CAPTURE_QUEUE, 100);
            assertThat(received).isNotNull();
            assertThat(received.enrollmentId()).isEqualTo(enrollmentId.toString());
            assertThat(received.enrollmentData().paymentType()).isEqualTo(PaymentType.CREDIT_CARD);
        });
    }

    @Test
    void publishInvoiceEnrollment_landsOnIntakeQueue() {
        var enrollmentId = UUID.randomUUID();
        var event = new EnrollmentEvent(Instant.now(), enrollmentData(enrollmentId, PaymentType.INVOICE));

        publisher.publish(event);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var received = (EnrollmentEvent) rabbitTemplate.receiveAndConvert(INTAKE_CAPTURE_QUEUE, 100);
            assertThat(received).isNotNull();
            assertThat(received.enrollmentId()).isEqualTo(enrollmentId.toString());
            assertThat(received.enrollmentData().paymentType()).isEqualTo(PaymentType.INVOICE);
        });
    }

    @Test
    void unroutablePublishThrowsAmqpException() {
        // Remove the binding so the intake exchange has no queue for our routing key.
        // mandatory=true + ReturnsCallback then surfaces the publish as AmqpException.
        var productionBinding = BindingBuilder.bind(
                        QueueBuilder.durable(AmqpConfig.ENROLLMENT_INTAKE_QUEUE).build())
                .to(intakeExchange)
                .with(AmqpConfig.ENROLLMENT_INTAKE_ROUTING_KEY);
        rabbitAdmin.removeBinding(productionBinding);
        rabbitAdmin.deleteQueue(INTAKE_CAPTURE_QUEUE);

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
