package dev.sindic.enrollmenthub.geoscoring.amqp;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AmqpConfigTest {

    @Test
    void deadLetterQueue_declaresSevenDayMessageTtl() {
        var dlq = new AmqpConfig().deadLetterQueue();

        assertThat(dlq.getArguments())
                .containsEntry("x-message-ttl", (int) Duration.ofDays(7).toMillis());
    }
}
