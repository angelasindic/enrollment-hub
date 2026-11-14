package dev.sindic.enrollmenthub.geoscoring.amqp;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.amqp.support.converter.MessageConversionException;

import static org.assertj.core.api.Assertions.assertThat;

class AmqpConfigTest {

    @Test
    void listenerRetryPolicy_doesNotRetryAMessageThatCannotBeConverted() {
        // The container hands the failure over wrapped: ListenerExecutionFailedException ←
        // MessageConversionException ← the Jackson or constructor failure. Redelivery brings the
        // same bytes, so the only useful destination is the DLQ.
        var failure = new ListenerExecutionFailedException("Listener threw exception",
                new MessageConversionException("Failed to convert Message content",
                        new IllegalArgumentException("not a valid message")));

        assertThat(AmqpConfig.listenerRetryPolicy().shouldRetry(failure)).isFalse();
    }

    @Test
    void listenerRetryPolicy_retriesAFailureInTheHandler() {
        var failure = new ListenerExecutionFailedException("Listener threw exception",
                new AmqpException("channel reset"));

        assertThat(AmqpConfig.listenerRetryPolicy().shouldRetry(failure)).isTrue();
    }
}
