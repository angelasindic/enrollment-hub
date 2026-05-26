package dev.sindic.enrollmenthub.frauddetection.amqp;

import dev.sindic.enrollmenthub.contracts.domain.Address;
import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;
import dev.sindic.enrollmenthub.contracts.domain.PaymentType;
import dev.sindic.enrollmenthub.contracts.domain.Person;
import dev.sindic.enrollmenthub.contracts.events.FraudCheckRequest;
import dev.sindic.enrollmenthub.contracts.events.FraudCheckResult;
import dev.sindic.enrollmenthub.contracts.events.SignalOutcome;
import dev.sindic.enrollmenthub.frauddetection.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Drives the fraud-detection listener over a real broker. The request queue and its DLX/DLQ are
 * owned by the decision-engine in production (ADR-003 §Channel ownership); since the decision-engine
 * is not present here, {@link RequestTopology} declares that topology plus a capture queue bound to
 * the result exchange.
 */
class FraudCheckRequestListenerIT extends BaseIntegrationTest {

    private static final String REQUEST_EXCHANGE     = "enrollment.check.request";
    private static final String FRAUD_KEY            = "fraud.check";
    private static final String REQUEST_QUEUE        = "fraud.detection.requests.queue";
    private static final String REQUEST_DLX          = "fraud.detection.requests.dlx";
    private static final String REQUEST_DLQ          = "fraud.detection.requests.queue.dlq";
    private static final String REQUEST_DLQ_RK       = "fraud.detection.requests.dead-letter";
    private static final String RESULT_EXCHANGE      = "enrollment.check.result";
    private static final String RESULT_CAPTURE_QUEUE = "test.fraud.result.capture";

    @Autowired RabbitTemplate rabbitTemplate;

    @Test
    void handleFraudCheckRequest_publishesOkResult() {
        var enrollmentId = UUID.randomUUID();
        rabbitTemplate.convertAndSend(REQUEST_EXCHANGE, FRAUD_KEY, request(enrollmentId));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var result = (FraudCheckResult) rabbitTemplate.receiveAndConvert(RESULT_CAPTURE_QUEUE, 100);
            assertThat(result).isNotNull();
            assertThat(result.enrollmentId()).isEqualTo(enrollmentId);
            assertThat(result.outcome()).isEqualTo(SignalOutcome.OK);
        });
    }

    @Test
    void poisonPill_routedToDeadLetterQueue() {
        var poison = MessageBuilder.withBody("{\"garbage\": true}".getBytes())
                .setContentType(MessageProperties.CONTENT_TYPE_JSON).build();
        rabbitTemplate.send(REQUEST_EXCHANGE, FRAUD_KEY, poison);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var dlqMessage = rabbitTemplate.receive(REQUEST_DLQ, 100);
            assertThat(dlqMessage).isNotNull();
            assertThat(new String(dlqMessage.getBody())).contains("garbage");
        });
    }

    private static FraudCheckRequest request(UUID enrollmentId) {
        var person  = new Person("Ada", "Lovelace", "ada@example.com", "+49123");
        var address = new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE");
        return new FraudCheckRequest(new EnrollmentData(enrollmentId, PaymentType.INVOICE, person, address, address));
    }

    @TestConfiguration
    static class RequestTopology {

        @Bean
        DirectExchange testCheckRequestExchange() {
            return new DirectExchange(REQUEST_EXCHANGE, true, false);
        }

        @Bean
        DirectExchange testFraudRequestDlx() {
            return new DirectExchange(REQUEST_DLX, true, false);
        }

        @Bean
        Queue testFraudRequestQueue() {
            return QueueBuilder.durable(REQUEST_QUEUE)
                    .deadLetterExchange(REQUEST_DLX)
                    .deadLetterRoutingKey(REQUEST_DLQ_RK)
                    .build();
        }

        @Bean
        Queue testFraudRequestDlq() {
            return QueueBuilder.durable(REQUEST_DLQ).build();
        }

        @Bean
        Binding testFraudRequestBinding() {
            return BindingBuilder.bind(testFraudRequestQueue()).to(testCheckRequestExchange()).with(FRAUD_KEY);
        }

        @Bean
        Binding testFraudRequestDlqBinding() {
            return BindingBuilder.bind(testFraudRequestDlq()).to(testFraudRequestDlx()).with(REQUEST_DLQ_RK);
        }

        @Bean
        Queue testResultCaptureQueue() {
            return QueueBuilder.nonDurable(RESULT_CAPTURE_QUEUE).build();
        }

        @Bean
        Binding testResultCaptureBinding() {
            return BindingBuilder.bind(testResultCaptureQueue())
                    .to(new DirectExchange(RESULT_EXCHANGE, true, false)).with(FRAUD_KEY);
        }
    }
}
