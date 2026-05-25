package dev.sindic.enrollmenthub.geoscoring.amqp;

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

    static final String PUBLISH_FAILURE_METRIC = "geo_scoring_publish_failures_total";

    // Request queue — owned and declared by the decision-engine (ADR-003 §Channel ownership).
    // Geo-scoring attaches a listener by name and does not redeclare it.
    static final String REQUEST_QUEUE = "geo.scoring.requests.queue";

    // Result channel — geo-scoring publishes GeoScoreResult here for the decision-engine.
    static final String RESULT_EXCHANGE    = "enrollment.check.result";
    static final String RESULT_ROUTING_KEY = "geo.score";

    private static final int    MAX_RETRIES      = 3;
    private static final long   INITIAL_INTERVAL = 1_000L;
    private static final double MULTIPLIER       = 2.0;
    private static final long   MAX_INTERVAL     = 10_000L;

    /**
     * Steady-state and burst concurrency for the listener container (M2 in the geo-scoring
     * review). Threads are virtual ({@link VirtualThreadTaskExecutor}), so the cost of holding
     * idle consumers is dominated by the Rabbit channel each one owns, not by OS threads.
     * 8/24 keeps in-flight load on self-hosted Nominatim and Redis bounded while still
     * absorbing realistic enrollment bursts.
     */
    private static final int CONCURRENT_CONSUMERS     = 8;
    private static final int MAX_CONCURRENT_CONSUMERS = 24;

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
     * RabbitTemplate with publisher confirms, mandatory publishing, and observability
     * hooks wired in. See ADR-009 §Delivery Semantics for the full pattern stack:
     *
     * <ul>
     *   <li><b>Confirms</b> (Guaranteed Delivery, EIP) — combined with
     *       {@code waitForConfirmsOrDie} on the publish path, a lost/nacked broker
     *       confirm surfaces as an exception so the listener retry path re-publishes.</li>
     *   <li><b>Mandatory + ReturnsCallback</b> — an unroutable message (wrong exchange,
     *       missing binding, typo'd routing key) is returned by the broker rather than
     *       silently dropped. The return is recorded on the per-publish
     *       {@code CorrelationData}; the publisher checks for it after
     *       {@code waitForConfirmsOrDie} and throws to trigger retry.</li>
     *   <li><b>Metrics</b> — nacks and returns increment
     *       {@value #PUBLISH_FAILURE_METRIC} (tagged by {@code reason}) so ops can
     *       alert on publish failures independently of listener-level retries.</li>
     * </ul>
     */
    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                  JacksonJsonMessageConverter messageConverter,
                                  MeterRegistry meterRegistry) {
        var nackCounter = Counter.builder(PUBLISH_FAILURE_METRIC)
                .tag("reason", "nack")
                .description("RabbitMQ publisher confirm nacks for geo-scoring events")
                .register(meterRegistry);
        var returnCounter = Counter.builder(PUBLISH_FAILURE_METRIC)
                .tag("reason", "returned")
                .description("Unroutable geo-scoring events returned by the broker")
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

        // Global observability hook. The publish path still needs to inspect
        // CorrelationData.getReturned() after waitForConfirmsOrDie to convert the
        // return into a thrown exception, because ReturnsCallback runs on the AMQP
        // I/O thread and cannot propagate back to the listener thread.
        template.setReturnsCallback(returned -> {
            log.error("RabbitMQ returned unroutable message exchange={} routingKey={} replyCode={} replyText={}",
                    returned.getExchange(), returned.getRoutingKey(),
                    returned.getReplyCode(), returned.getReplyText());
            returnCounter.increment();
        });

        return template;
    }

    /**
     * Listener container factory with virtual thread executor (ADR-008). Spring AMQP 4.x accepts
     * any {@code TaskExecutor} on the container factory; {@link VirtualThreadTaskExecutor} is wired
     * here for the {@code amqp-geo-} thread-name prefix on top of the application-wide
     * {@code spring.threads.virtual.enabled=true}.
     */
    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            JacksonJsonMessageConverter messageConverter) {
        var factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        // The request queue is owned/declared by the decision-engine (ADR-003 §Channel ownership),
        // not by geo-scoring. Tolerate it not existing yet at startup — the container retries
        // declaration instead of failing fatally if geo-scoring starts before the decision-engine.
        factory.setMissingQueuesFatal(false);
        factory.setTaskExecutor(new VirtualThreadTaskExecutor("amqp-geo-"));
        factory.setConcurrentConsumers(CONCURRENT_CONSUMERS);
        factory.setMaxConcurrentConsumers(MAX_CONCURRENT_CONSUMERS);
        factory.setObservationEnabled(true);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .maxRetries(MAX_RETRIES)
                .backOffOptions(INITIAL_INTERVAL, MULTIPLIER, MAX_INTERVAL)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build());
        return factory;
    }
}
