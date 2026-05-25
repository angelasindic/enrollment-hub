package dev.sindic.enrollmenthub.frauddetection.amqp;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Configuration
@EnableConfigurationProperties(AmqpProperties.class)
class AmqpConfig {

    static final String PUBLISH_FAILURE_METRIC = "fraud_detection_publish_failures_total";

    // Request queue — owned and declared by the decision-engine (ADR-003 §Channel ownership).
    // Fraud detection attaches a listener by name and does not redeclare it.
    static final String REQUEST_QUEUE = "fraud.detection.requests.queue";

    // Result channel — fraud detection publishes FraudCheckResult here for the decision-engine.
    static final String RESULT_EXCHANGE    = "enrollment.check.result";
    static final String RESULT_ROUTING_KEY = "fraud.check";

    private static final int    MAX_RETRIES      = 3;
    private static final long   INITIAL_INTERVAL = 1_000L;
    private static final double MULTIPLIER       = 2.0;
    private static final long   MAX_INTERVAL     = 10_000L;

    /** Result exchange (shared; also declared by the decision-engine). Declared idempotently. */
    @Bean
    DirectExchange resultExchange() {
        return new DirectExchange(RESULT_EXCHANGE, true, false);
    }

    /**
     * Reuses the auto-configured JsonMapper (fail-on-unknown-properties=false via application.yml)
     * for both incoming deserialization and outgoing serialization on the RabbitTemplate.
     */
    @Bean
    JacksonJsonMessageConverter messageConverter(JsonMapper jsonMapper) {
        return new JacksonJsonMessageConverter(jsonMapper);
    }

    /**
     * RabbitTemplate with publisher confirms, mandatory publishing, and observability hooks
     * (ADR-009 §Delivery Semantics): a nacked confirm or an unroutable return surfaces as an
     * exception on the publish path so the listener retry interceptor re-invokes; nacks and returns
     * increment {@value #PUBLISH_FAILURE_METRIC} tagged by {@code reason}.
     */
    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                  JacksonJsonMessageConverter messageConverter,
                                  MeterRegistry meterRegistry) {
        var nackCounter = Counter.builder(PUBLISH_FAILURE_METRIC)
                .tag("reason", "nack")
                .description("RabbitMQ publisher confirm nacks for fraud-detection events")
                .register(meterRegistry);
        var returnCounter = Counter.builder(PUBLISH_FAILURE_METRIC)
                .tag("reason", "returned")
                .description("Unroutable fraud-detection events returned by the broker")
                .register(meterRegistry);

        var template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        template.setMandatory(true);

        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("RabbitMQ publisher nack correlationId={} cause={}",
                        correlationData != null ? correlationData.getId() : null, cause);
                nackCounter.increment();
            }
        });

        template.setReturnsCallback(returned -> {
            log.error("RabbitMQ returned unroutable message exchange={} routingKey={} replyCode={} replyText={}",
                    returned.getExchange(), returned.getRoutingKey(),
                    returned.getReplyCode(), returned.getReplyText());
            returnCounter.increment();
        });

        return template;
    }

    /**
     * Listener container factory with a virtual-thread executor (ADR-008, {@code amqp-fraud-}
     * thread-name prefix), exponential-backoff retry, and non-requeueing recovery to the DLQ on
     * exhaustion.
     */
    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            JacksonJsonMessageConverter messageConverter) {
        var factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        // The request queue is owned/declared by the decision-engine (ADR-003 §Channel ownership),
        // not by fraud-detection. Tolerate it not existing yet at startup — the container retries
        // declaration instead of failing fatally if fraud-detection starts before the decision-engine.
        factory.setMissingQueuesFatal(false);
        factory.setTaskExecutor(new VirtualThreadTaskExecutor("amqp-fraud-"));
        factory.setObservationEnabled(true);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .maxRetries(MAX_RETRIES)
                .backOffOptions(INITIAL_INTERVAL, MULTIPLIER, MAX_INTERVAL)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build());
        return factory;
    }
}
