package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.contracts.events.EnrollmentEvent;
import dev.sindic.enrollmenthub.decisionengine.BaseIntegrationTest;
import dev.sindic.enrollmenthub.decisionengine.amqp.AmqpConfig;
import dev.sindic.enrollmenthub.decisionengine.domain.*;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentRepository;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end coverage of the broker-backed intake pattern (ADR-003 §Layer 1).
 *
 * <ul>
 *   <li>{@code receiveEnrollment} publishes to {@code enrollment.intake}. No DB
 *       row exists synchronously.</li>
 *   <li>The live {@code EnrollmentIntakeListener} consumes from the intake queue
 *       and triggers {@code processEnrollment}, which persists the row and
 *       publishes to {@code enrollment.events}.</li>
 *   <li>If the intake publish itself is unroutable (broken topology), no row is
 *       created — the publisher confirm + mandatory return surface as
 *       {@link AmqpException}.</li>
 * </ul>
 *
 * The "publish-fails → tx rolls back" property of {@code processEnrollment} is
 * covered separately (mocked) in {@link EnrollmentIntakeServiceTest}; here we
 * focus on the cross-component wiring.
 */
class EnrollmentIntakeServiceIT extends BaseIntegrationTest {

    private static final String CREDIT_CARD_CAPTURE_QUEUE = "test.service.events.credit_card";
    private static final String INVOICE_CAPTURE_QUEUE     = "test.service.events.invoice";

    @Autowired
    EnrollmentIntakeService service;
    @Autowired EnrollmentRepository repository;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired RabbitAdmin rabbitAdmin;
    @Autowired @Qualifier("enrollmentExchange") TopicExchange enrollmentExchange;

    @BeforeEach
    void declareCaptureQueues() {
        var creditQueue  = QueueBuilder.nonDurable(CREDIT_CARD_CAPTURE_QUEUE).build();
        var invoiceQueue = QueueBuilder.nonDurable(INVOICE_CAPTURE_QUEUE).build();
        rabbitAdmin.declareQueue(creditQueue);
        rabbitAdmin.declareQueue(invoiceQueue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(creditQueue)
                .to(enrollmentExchange).with(AmqpConfig.ROUTING_KEY_CREDIT_CARD));
        rabbitAdmin.declareBinding(BindingBuilder.bind(invoiceQueue)
                .to(enrollmentExchange).with(AmqpConfig.ROUTING_KEY_INVOICE));
    }

    @AfterEach
    void cleanupCaptureQueues() {
        rabbitAdmin.deleteQueue(CREDIT_CARD_CAPTURE_QUEUE);
        rabbitAdmin.deleteQueue(INVOICE_CAPTURE_QUEUE);
    }

    @Test
    void creditCardEndToEnd_persistsRow_andPublishesToEventsExchange() {
        var command = creditCardCommand();

        var response = service.receiveEnrollment(command);

        // The intake listener processes asynchronously — await both the row
        // appearing AND the EnrollmentEvent landing on the events-exchange queue.
        var enrollmentId = UUID.fromString(response.enrollmentId());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var loaded = repository.findById(enrollmentId);
            assertThat(loaded).isPresent();
            var entity = loaded.get();
            assertThat(entity.getPaymentType()).isEqualTo(PaymentType.CREDIT_CARD);
            assertThat(entity.getSignals()).containsOnlyKeys(SignalConfig.GEO_SCORE, SignalConfig.FRAUD_CHECK);
            assertThat(entity.getSignals().get(SignalConfig.GEO_SCORE).processingState())
                    .isEqualTo(SignalProcessingState.PENDING);
            assertThat(entity.getSignals().get(SignalConfig.FRAUD_CHECK).processingState())
                    .isEqualTo(SignalProcessingState.PENDING);
            assertThat(entity.getDecisionResult()).isNull();
        });

        // Routing-key isolation is covered directly by EnrollmentIntakePublisherIT
        // and EnrollmentDecisionPublisherIT; here we only assert the positive end-to-end.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var received = (EnrollmentEvent) rabbitTemplate.receiveAndConvert(
                    CREDIT_CARD_CAPTURE_QUEUE, 100);
            assertThat(received).isNotNull();
            assertThat(received.enrollmentId()).isEqualTo(response.enrollmentId());
            assertThat(received.enrollmentData().paymentType().name()).isEqualTo("CREDIT_CARD");
        });
    }

    @Test
    void invoiceEndToEnd_persistsRow_andPublishesToEventsExchange() {
        var command = invoiceCommand();

        var response = service.receiveEnrollment(command);
        var enrollmentId = UUID.fromString(response.enrollmentId());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var loaded = repository.findById(enrollmentId);
            assertThat(loaded).isPresent();
            assertThat(loaded.get().getPaymentType()).isEqualTo(PaymentType.INVOICE);
            assertThat(loaded.get().getSignals()).containsOnlyKeys(SignalConfig.FRAUD_CHECK);
        });

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var received = (EnrollmentEvent) rabbitTemplate.receiveAndConvert(
                    INVOICE_CAPTURE_QUEUE, 100);
            assertThat(received).isNotNull();
            assertThat(received.enrollmentData().paymentType().name()).isEqualTo("INVOICE");
        });
    }

    /**
     * If the intake exchange itself has no queue bound for the routing key, the
     * publisher confirm (mandatory=true) surfaces an AmqpException and no
     * correlation row is ever created — Layer 1 acts as the durability boundary.
     */
    @Test
    void unroutableIntakePublishFails_andNoRowPersisted() {
        // Remove the production binding (not the queue) so the intake exchange
        // has no queue listening for our routing key. Deleting the queue
        // itself would kick the SimpleMessageListenerContainer's consumer into
        // recovery mode and pollute the next IT.
        var productionBinding = BindingBuilder.bind(
                        QueueBuilder.durable(AmqpConfig.ENROLLMENT_INTAKE_QUEUE).build())
                .to(new TopicExchange(AmqpConfig.ENROLLMENT_INTAKE_EXCHANGE))
                .with("enrollment.created.*");
        rabbitAdmin.removeBinding(productionBinding);

        long rowsBefore = repository.count();

        try {
            assertThatThrownBy(() -> service.receiveEnrollment(creditCardCommand()))
                    .isInstanceOf(AmqpException.class)
                    .hasMessageContaining("unroutable");

            assertThat(repository.count()).isEqualTo(rowsBefore);
        } finally {
            rabbitAdmin.declareBinding(productionBinding);
        }
    }

    private static EnrollmentCommand creditCardCommand() {
        return new EnrollmentCommand(
                UUID.randomUUID(),
                PaymentType.CREDIT_CARD,
                new Person("Ada", "Lovelace", "ada@example.com", "+49123"),
                new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE"),
                new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE"));
    }

    private static EnrollmentCommand invoiceCommand() {
        return new EnrollmentCommand(
                UUID.randomUUID(),
                PaymentType.INVOICE,
                new Person("Ada", "Lovelace", "ada@example.com", "+49123"),
                new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE"),
                new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE"));
    }
}
