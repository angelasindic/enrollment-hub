package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.contracts.events.EnrollmentAccepted;
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

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class CreateEnrollmentServiceIT extends BaseIntegrationTest {

    private static final String CREDIT_CARD_CAPTURE_QUEUE = "test.service.capture.credit_card";
    private static final String INVOICE_CAPTURE_QUEUE     = "test.service.capture.invoice";

    @Autowired CreateEnrollmentService service;
    @Autowired EnrollmentRepository repository;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired RabbitAdmin rabbitAdmin;
    @Autowired TopicExchange enrollmentExchange;

    @BeforeEach
    void declareCaptureQueues() {
        var creditQueue  = QueueBuilder.nonDurable(CREDIT_CARD_CAPTURE_QUEUE).build();
        var invoiceQueue = QueueBuilder.nonDurable(INVOICE_CAPTURE_QUEUE).build();
        rabbitAdmin.declareQueue(creditQueue);
        rabbitAdmin.declareQueue(invoiceQueue);
        rabbitAdmin.declareBinding(
                BindingBuilder.bind(creditQueue).to(enrollmentExchange).with(AmqpConfig.ROUTING_KEY_CREDIT_CARD));
        rabbitAdmin.declareBinding(
                BindingBuilder.bind(invoiceQueue).to(enrollmentExchange).with(AmqpConfig.ROUTING_KEY_INVOICE));
    }

    @AfterEach
    void cleanupCaptureQueues() {
        rabbitAdmin.deleteQueue(CREDIT_CARD_CAPTURE_QUEUE);
        rabbitAdmin.deleteQueue(INVOICE_CAPTURE_QUEUE);
    }

    @Test
    void creditCardRequestPersistsCorrelationRow() {
        var response = service.createEnrollment(creditCardCommand());

        assertThat(response.status()).isEqualTo(PendingEnrollmentResponse.Status.ACCEPTED);

        var entity = repository.findById(response.requestId()).orElseThrow();
        assertThat(entity.getPaymentType()).isEqualTo(PaymentType.CREDIT_CARD);
        assertThat(entity.getSignals()).containsOnlyKeys(SignalConfig.GEO_SCORE, SignalConfig.FRAUD_CHECK);
        assertThat(entity.getSignals().get(SignalConfig.GEO_SCORE).processingState())
                .isEqualTo(SignalProcessingState.PENDING);
        assertThat(entity.getSignals().get(SignalConfig.FRAUD_CHECK).processingState())
                .isEqualTo(SignalProcessingState.PENDING);
        assertThat(entity.getCreatedAt()).isNotNull();
        assertThat(entity.getTimeoutAt()).isAfter(entity.getCreatedAt());
        assertThat(entity.getDecisionResult()).isNull();
    }

    @Test
    void invoiceRequestPersistsCorrelationRow() {
        var response = service.createEnrollment(invoiceCommand());

        var entity = repository.findById(response.requestId()).orElseThrow();
        assertThat(entity.getPaymentType()).isEqualTo(PaymentType.INVOICE);
        assertThat(entity.getSignals()).containsOnlyKeys(SignalConfig.FRAUD_CHECK);
        assertThat(entity.getSignals().get(SignalConfig.FRAUD_CHECK).processingState())
                .isEqualTo(SignalProcessingState.PENDING);
    }

    @Test
    void creditCardRequestPublishesEnrollmentAcceptedWithPreservedCorrelationId() {
        var response = service.createEnrollment(creditCardCommand());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var received = (EnrollmentAccepted) rabbitTemplate.receiveAndConvert(
                    CREDIT_CARD_CAPTURE_QUEUE, 100);
            assertThat(received).isNotNull();
            assertThat(received.requestId()).isEqualTo(response.requestId());
            assertThat(received.enrollmentData().paymentType().name()).isEqualTo("CREDIT_CARD");
        });
        assertThat(rabbitTemplate.receive(INVOICE_CAPTURE_QUEUE, 100)).isNull();
    }

    @Test
    void invoiceRequestPublishesEnrollmentAcceptedWithPreservedCorrelationId() {
        var response = service.createEnrollment(invoiceCommand());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var received = (EnrollmentAccepted) rabbitTemplate.receiveAndConvert(
                    INVOICE_CAPTURE_QUEUE, 100);
            assertThat(received).isNotNull();
            assertThat(received.requestId()).isEqualTo(response.requestId());
            assertThat(received.enrollmentData().paymentType().name()).isEqualTo("INVOICE");
        });
        assertThat(rabbitTemplate.receive(CREDIT_CARD_CAPTURE_QUEUE, 100)).isNull();
    }

    /**
     * ADR-015 §Decision — publish happens inside the transaction, so an unroutable
     * publish rolls back the correlation row: no orphaned request is left for
     * workers that will never hear about it.
     */
    @Test
    void publishFailureRollsBackCorrelationRow() {
        rabbitAdmin.deleteQueue(CREDIT_CARD_CAPTURE_QUEUE);
        rabbitAdmin.deleteQueue(INVOICE_CAPTURE_QUEUE);

        long rowCountBefore = repository.count();

        assertThatThrownBy(() -> service.createEnrollment(creditCardCommand()))
                .isInstanceOf(AmqpException.class);

        assertThat(repository.count()).isEqualTo(rowCountBefore);
    }

    private static EnrollmentCommand creditCardCommand() {
        return new EnrollmentCommand(
                PaymentType.CREDIT_CARD,
                new Person("Ada", "Lovelace", "ada@example.com", "+49123"),
                new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE"),
                new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE"));
    }

    private static EnrollmentCommand invoiceCommand() {
        return new EnrollmentCommand(
                PaymentType.INVOICE,
                new Person("Ada", "Lovelace", "ada@example.com", "+49123"),
                new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE"),
                new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE"));
    }
}
