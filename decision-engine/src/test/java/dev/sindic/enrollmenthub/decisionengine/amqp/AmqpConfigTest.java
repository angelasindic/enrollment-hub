package dev.sindic.enrollmenthub.decisionengine.amqp;

import dev.sindic.enrollmenthub.contracts.domain.PaymentType;
import dev.sindic.enrollmenthub.decisionengine.service.UnknownCorrelationException;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;

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
        // A signal result for an unknown requestId is structurally non-retryable —
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
}
