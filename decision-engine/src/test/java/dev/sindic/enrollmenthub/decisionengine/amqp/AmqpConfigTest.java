package dev.sindic.enrollmenthub.decisionengine.amqp;

import dev.sindic.enrollmenthub.contracts.domain.PaymentType;
import dev.sindic.enrollmenthub.decisionengine.service.UnknownCorrelationException;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AmqpConfigTest {

    @Test
    void routingKeyForCreditCard() {
        assertThat(AmqpConfig.routingKeyFor(PaymentType.CREDIT_CARD))
                .isEqualTo("enrollment.created.credit_card");
    }

    @Test
    void routingKeyForInvoice() {
        assertThat(AmqpConfig.routingKeyFor(PaymentType.INVOICE))
                .isEqualTo("enrollment.created.invoice");
    }

    @Test
    void listenerRetryPolicy_doesNotRetryUnknownCorrelation() {
        // A signal result for an unknown enrollmentId is structurally non-retryable —
        // re-invoking the listener cannot make the correlation row appear.
        var policy = AmqpConfig.listenerRetryPolicy();

        assertThat(policy.shouldRetry(new UnknownCorrelationException(UUID.randomUUID())))
                .isFalse();
    }

    @Test
    void listenerRetryPolicy_doesNotRetryWhenUnknownCorrelationIsTheCause() {
        // RetryPolicy.Builder#excludes walks the cause chain — verify that a
        // wrapped UnknownCorrelationException is still treated as non-retryable
        // so callers can wrap freely without re-introducing retries.
        var policy = AmqpConfig.listenerRetryPolicy();
        var wrapped = new RuntimeException("outer wrapper",
                new UnknownCorrelationException(UUID.randomUUID()));

        assertThat(policy.shouldRetry(wrapped)).isFalse();
    }

    @Test
    void listenerRetryPolicy_retriesTransientRuntimeException() {
        // Anything not on the excludes list retries — typical case: broker
        // hiccup, transient DB error, deserialization that succeeds on a re-poll.
        var policy = AmqpConfig.listenerRetryPolicy();

        assertThat(policy.shouldRetry(new RuntimeException("transient broker hiccup")))
                .isTrue();
    }

    @Test
    void listenerRetryPolicy_retriesAmqpException() {
        // AMQP-layer failures (channel resets, broker reconnects) are explicitly
        // the case the retry policy exists for.
        var policy = AmqpConfig.listenerRetryPolicy();

        assertThat(policy.shouldRetry(new AmqpException("channel reset"))).isTrue();
    }

    @Test
    void checkChannelTopology_declaresPerSignalRequestAndResultQueues() {
        var declarables = new AmqpConfig().checkChannelTopology().getDeclarables();

        var exchangeNames = declarables.stream()
                .filter(Exchange.class::isInstance).map(d -> ((Exchange) d).getName()).toList();
        var queueNames = declarables.stream()
                .filter(Queue.class::isInstance).map(d -> ((Queue) d).getName()).toList();

        assertThat(exchangeNames).contains("enrollment.check.request", "enrollment.check.result");
        assertThat(queueNames).contains(
                "geo.scoring.requests.queue", "fraud.detection.requests.queue",
                "decision-engine.geo-score.results.queue", "decision-engine.fraud-check.results.queue",
                "geo.scoring.requests.queue.dlq", "fraud.detection.requests.queue.dlq",
                "decision-engine.geo-score.results.queue.dlq", "decision-engine.fraud-check.results.queue.dlq");
    }

    @Test
    void checkChannelTopology_bindsRequestQueuesBySignalName() {
        var requestBindings = new AmqpConfig().checkChannelTopology().getDeclarables().stream()
                .filter(Binding.class::isInstance).map(Binding.class::cast)
                .filter(b -> b.getExchange().equals("enrollment.check.request"))
                .toList();

        assertThat(requestBindings).anySatisfy(b -> {
            assertThat(b.getDestination()).isEqualTo("geo.scoring.requests.queue");
            assertThat(b.getRoutingKey()).isEqualTo("geo.score");
        });
        assertThat(requestBindings).anySatisfy(b -> {
            assertThat(b.getDestination()).isEqualTo("fraud.detection.requests.queue");
            assertThat(b.getRoutingKey()).isEqualTo("fraud.check");
        });
    }
}
