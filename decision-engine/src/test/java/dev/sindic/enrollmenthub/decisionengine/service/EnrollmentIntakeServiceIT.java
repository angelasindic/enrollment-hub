package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.contracts.events.FraudCheckRequest;
import dev.sindic.enrollmenthub.contracts.events.GeoScoreRequest;
import dev.sindic.enrollmenthub.decisionengine.BaseIntegrationTest;
import dev.sindic.enrollmenthub.decisionengine.amqp.AmqpConfig;
import dev.sindic.enrollmenthub.decisionengine.domain.*;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end coverage of the broker-backed intake pattern (ADR-003 §Layer 1) and the per-signal
 * dispatch (§Layer 2).
 *
 * <ul>
 *   <li>{@code receiveEnrollment} publishes to {@code enrollment.intake}. No DB row exists
 *       synchronously.</li>
 *   <li>The live {@code EnrollmentIntakeListener} consumes from the intake queue and triggers
 *       {@code processEnrollment}, which persists the row and dispatches one command per applicable
 *       signal to {@code enrollment.check.request} (geo.score + fraud.check on credit-card,
 *       fraud.check only on invoice).</li>
 *   <li>If the intake publish itself is unroutable (broken topology), no row is created — the
 *       publisher confirm + mandatory return surface as {@link AmqpException}.</li>
 * </ul>
 */
class EnrollmentIntakeServiceIT extends BaseIntegrationTest {

    private static final String GEO_CAPTURE_QUEUE   = "test.service.geo.score";
    private static final String FRAUD_CAPTURE_QUEUE = "test.service.fraud.check";

    @Autowired EnrollmentIntakeService service;
    @Autowired EnrollmentRepository repository;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired RabbitAdmin rabbitAdmin;

    @BeforeEach
    void declareCaptureQueues() {
        var requestExchange = new DirectExchange(AmqpConfig.CHECK_REQUEST_EXCHANGE);
        var geoQueue   = QueueBuilder.nonDurable(GEO_CAPTURE_QUEUE).build();
        var fraudQueue = QueueBuilder.nonDurable(FRAUD_CAPTURE_QUEUE).build();
        rabbitAdmin.declareQueue(geoQueue);
        rabbitAdmin.declareQueue(fraudQueue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(geoQueue).to(requestExchange).with(AmqpConfig.GEO_SCORE_KEY));
        rabbitAdmin.declareBinding(BindingBuilder.bind(fraudQueue).to(requestExchange).with(AmqpConfig.FRAUD_CHECK_KEY));
    }

    @AfterEach
    void cleanupCaptureQueues() {
        rabbitAdmin.deleteQueue(GEO_CAPTURE_QUEUE);
        rabbitAdmin.deleteQueue(FRAUD_CAPTURE_QUEUE);
    }

    @Test
    void creditCardEndToEnd_persistsRow_andDispatchesGeoAndFraudCommands() {
        var command = creditCardCommand();

        var response = service.receiveEnrollment(command);
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

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var geo = (GeoScoreRequest) rabbitTemplate.receiveAndConvert(GEO_CAPTURE_QUEUE, 100);
            assertThat(geo).isNotNull();
            assertThat(geo.enrollmentId()).isEqualTo(enrollmentId);
            assertThat(geo.shippingAddress().city()).isEqualTo("Berlin");
        });
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var fraud = (FraudCheckRequest) rabbitTemplate.receiveAndConvert(FRAUD_CAPTURE_QUEUE, 100);
            assertThat(fraud).isNotNull();
            assertThat(fraud.enrollmentId()).isEqualTo(enrollmentId);
            assertThat(fraud.enrollmentData().paymentType().name()).isEqualTo("CREDIT_CARD");
        });
    }

    @Test
    void invoiceEndToEnd_persistsRow_andDispatchesFraudCommandOnly() {
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
            var fraud = (FraudCheckRequest) rabbitTemplate.receiveAndConvert(FRAUD_CAPTURE_QUEUE, 100);
            assertThat(fraud).isNotNull();
            assertThat(fraud.enrollmentData().paymentType().name()).isEqualTo("INVOICE");
        });
        // No geo.score command is dispatched on the invoice route.
        assertThat(rabbitTemplate.receiveAndConvert(GEO_CAPTURE_QUEUE, 100)).isNull();
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
                .to(new DirectExchange(AmqpConfig.ENROLLMENT_INTAKE_EXCHANGE))
                .with(AmqpConfig.ENROLLMENT_INTAKE_ROUTING_KEY);
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
