package dev.sindic.enrollmenthub.geoscoring.amqp;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
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

import java.time.Duration;

@Slf4j
@Configuration
@EnableConfigurationProperties(AmqpProperties.class)
class AmqpConfig {

    static final String PUBLISH_FAILURE_METRIC = "geo_scoring_publish_failures_total";

    static final String EXCHANGE           = "enrollment.events";
    static final String QUEUE              = "geo.scoring.queue";
    static final String ROUTING_KEY        = "enrollment.created.credit_card";
    static final String RESULT_ROUTING_KEY = "geo.score.completed";

    static final String DLX               = "geo.scoring.dlx";
    static final String DLQ               = "geo.scoring.queue.dlq";
    static final String DLQ_ROUTING_KEY   = "geo.scoring.dead-letter";

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

    /**
     * DLQ retention bound (geo-scoring review §C7 checklist). RabbitMQ drops messages whose
     * age exceeds the queue {@code x-message-ttl}; without this the DLQ would grow forever.
     * Seven days gives operators a working week to triage before automatic eviction.
     */
    private static final Duration DLQ_TTL = Duration.ofDays(7);

    /** Durable topic exchange shared across all services. Declared idempotently at startup. */
    @Bean
    TopicExchange enrollmentExchange() {
        return ExchangeBuilder.topicExchange(EXCHANGE).durable(true).build();
    }

    /** Consumer-owned durable queue — routes rejected messages to the dead-letter exchange. */
    @Bean
    Queue geoScoringQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX, true, false);
    }

    @Bean
    Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ)
                .ttl((int) DLQ_TTL.toMillis())
                .build();
    }

    @Bean
    Binding deadLetterBinding(Queue deadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(DLQ_ROUTING_KEY);
    }

    @Bean
    Gauge dlqDepthGauge(RabbitTemplate rabbitTemplate, MeterRegistry meterRegistry) {
        return Gauge.builder("rabbitmq.dlq.depth", rabbitTemplate, template -> {
                    try {
                        var ok = template.execute(ch -> ch.queueDeclarePassive(DLQ));
                        return ok != null ? (double) ok.getMessageCount() : 0.0;
                    } catch (Exception e) {
                        log.warn("Could not poll DLQ depth queue={}", DLQ);
                        return 0.0;
                    }
                })
                .tag("queue", DLQ)
                .description("Messages waiting in the geo-scoring dead-letter queue")
                .register(meterRegistry);
    }

    /** Binds geo-scoring queue to the CREDIT_CARD routing key only (ADR-003). */
    @Bean
    Binding geoScoringBinding(Queue geoScoringQueue, TopicExchange enrollmentExchange) {
        return BindingBuilder.bind(geoScoringQueue).to(enrollmentExchange).with(ROUTING_KEY);
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
