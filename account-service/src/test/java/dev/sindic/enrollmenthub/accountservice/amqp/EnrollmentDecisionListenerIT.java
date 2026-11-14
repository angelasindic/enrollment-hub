package dev.sindic.enrollmenthub.accountservice.amqp;

import dev.sindic.enrollmenthub.accountservice.BaseIntegrationTest;
import dev.sindic.enrollmenthub.accountservice.service.AccountProvisioningService;
import dev.sindic.enrollmenthub.contracts.domain.Address;
import dev.sindic.enrollmenthub.contracts.domain.EnrollmentSnapshot;
import dev.sindic.enrollmenthub.contracts.domain.PaymentType;
import dev.sindic.enrollmenthub.contracts.domain.Person;
import dev.sindic.enrollmenthub.contracts.events.DecisionResult;
import dev.sindic.enrollmenthub.contracts.events.EnrollmentDecisionEvent;
import dev.sindic.enrollmenthub.contracts.events.EnrollmentSignal;
import dev.sindic.enrollmenthub.contracts.events.RiskLevel;
import dev.sindic.enrollmenthub.contracts.events.SignalOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Drives the listener over a real broker. Unlike the signal workers, the account service declares
 * its own queue, binding and DLX/DLQ (ADR-13 §Channel Ownership), so the test publishes to the
 * decisions exchange exactly as the decision-engine does, with no test-side topology.
 */
class EnrollmentDecisionListenerIT extends BaseIntegrationTest {

    @Autowired RabbitTemplate rabbitTemplate;
    @Value("${spring.rabbitmq.listener.simple.prefetch}") int prefetch;
    @MockitoSpyBean AccountProvisioningService service;

    @Test
    void listenerConsumes_withThePrefetchFromTheConfiguration() throws Exception {
        // Asked of the broker, which enforces the limit: every consumer on the queue has to carry
        // the configured prefetch. A factory that skips Boot's listener properties consumes with
        // the Spring AMQP default of 250.
        var consumers = RABBITMQ.execInContainer(
                "rabbitmqctl", "--quiet", "list_consumers", "queue_name", "prefetch_count").getStdout();

        assertThat(consumers.lines().filter(line -> line.startsWith(AmqpConfig.DECISION_QUEUE + "\t")))
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line).endsWith("\t" + prefetch));
    }

    @Test
    void decisionPublishedToExchange_isRoutedToTheListener() {
        var event = decision(UUID.randomUUID());

        rabbitTemplate.convertAndSend(AmqpConfig.DECISION_EXCHANGE, AmqpConfig.DECISION_ROUTING_KEY, event);

        verify(service, timeout(15_000)).onDecision(argThat(e -> e.decisionId().equals(event.decisionId())));
    }

    @Test
    void redeliveredDecision_isHandledOnce() {
        var event = decision(UUID.randomUUID());
        var outcomes = new CopyOnWriteArrayList<Boolean>();
        doAnswer(invocation -> {
            var handled = (Boolean) invocation.callRealMethod();
            outcomes.add(handled);
            return handled;
        }).when(service).onDecision(argThat(e -> e.decisionId().equals(event.decisionId())));

        rabbitTemplate.convertAndSend(AmqpConfig.DECISION_EXCHANGE, AmqpConfig.DECISION_ROUTING_KEY, event);
        rabbitTemplate.convertAndSend(AmqpConfig.DECISION_EXCHANGE, AmqpConfig.DECISION_ROUTING_KEY, event);

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(outcomes).containsExactly(true, false));
    }

    @Test
    void poisonPill_routedToDeadLetterQueue() {
        var poison = MessageBuilder.withBody("{\"garbage\": true}".getBytes())
                .setContentType(MessageProperties.CONTENT_TYPE_JSON).build();
        rabbitTemplate.send(AmqpConfig.DECISION_EXCHANGE, AmqpConfig.DECISION_ROUTING_KEY, poison);

        // Well inside the 7 s the retry backoff adds up to (1 s + 2 s + 4 s): a message that
        // cannot be converted is dead-lettered on the first throw.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var dlqMessage = rabbitTemplate.receive(AmqpConfig.DECISION_DLQ, 100);
            assertThat(dlqMessage).isNotNull();
            assertThat(new String(dlqMessage.getBody())).contains("garbage");
        });
    }

    private static EnrollmentDecisionEvent decision(UUID decisionId) {
        var person   = new Person("Ada", "Lovelace", "ada@example.com", "+49123");
        var address  = new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE");
        var snapshot = new EnrollmentSnapshot(PaymentType.CREDIT_CARD, person, address, address);
        return new EnrollmentDecisionEvent(
                decisionId,
                snapshot,
                DecisionResult.APPROVED,
                Map.of(
                        "GEO_SCORE",   new EnrollmentSignal(null, RiskLevel.LOW, null),
                        "FRAUD_CHECK", new EnrollmentSignal(SignalOutcome.OK, null, null)),
                Instant.parse("2026-05-21T10:00:00Z"));
    }
}
