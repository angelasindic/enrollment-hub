package dev.sindic.enrollmenthub.decisionengine.amqp;

import dev.sindic.enrollmenthub.contracts.domain.PaymentType;
import dev.sindic.enrollmenthub.decisionengine.service.UnknownCorrelationException;
import io.micrometer.core.instrument.Counter;
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
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

/**
 * AMQP publisher configuration for the decision-engine.
 *
 * <h2>Routing topology (ADR-003)</h2>
 * The decision-engine publishes {@code EnrollmentAccepted} to a shared topic
 * exchange ({@link #EXCHANGE} = {@code enrollment.events}) with the routing key derived from the payment type:
 * <ul>
 *   <li>{@link PaymentType#CREDIT_CARD} → {@link #ROUTING_KEY_CREDIT_CARD}</li>
 *   <li>{@link PaymentType#INVOICE}     → {@link #ROUTING_KEY_INVOICE}</li>
 * </ul>
 * The mapping is centralised in {@link #routingKeyFor(PaymentType)}.
 *
 * <h2>Consumer-owned bindings (ADR-003 §Consumer-owned bindings)</h2>
 * The decision-engine <b>does not</b> declare consumer queues or bindings. Each
 * worker service owns its queue and binds it to the shared exchange.
 * Adding a new worker means declaring a queue + binding in that worker's AMQP
 * config. No decision-engine change is required — the topic exchange is the router.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(AmqpProperties.class)
public class AmqpConfig {


    public static final String ENROLLMENT_INTAKE_EXCHANGE         = "enrollment.intake";
    public static final String ENROLLMENT_INTAKE_QUEUE            = "enrollment.intake.queue";
    static final String ENROLLMENT_INTAKE_DLX         = "enrollment.intake.dlx";
    static final String ENROLLMENT_INTAKE_DLQ         = "enrollment.intake.queue.dlq";
    static final String ENROLLMENT_INTAKE_DLQ_RK      = "enrollment.intake.dead-letter";

    public static final String EXCHANGE                = "enrollment.events";
    public static final String ROUTING_KEY_CREDIT_CARD = "enrollment.created.credit_card";
    public static final String ROUTING_KEY_INVOICE     = "enrollment.created.invoice";

    // --- Outbound decisions topology (ADR-003 §Layer 3) ---

    public static final String DECISION_EXCHANGE    = "enrollment.decisions";
    public static final String DECISION_ROUTING_KEY = "enrollment.decision.completed";

    static final String PUBLISH_FAILURE_METRIC = "decisionengine_publish_failures_total";

    // --- Geo-score result consumer topology ---

    static final String GEO_SCORE_QUEUE       = "decision-engine.geo-score.queue";
    static final String GEO_SCORE_ROUTING_KEY = "geo.score.completed";
    static final String GEO_SCORE_DLX         = "decision-engine.geo-score.dlx";
    static final String GEO_SCORE_DLQ         = "decision-engine.geo-score.queue.dlq";
    static final String GEO_SCORE_DLQ_RK      = "decision-engine.geo-score.dead-letter";

    private static final int    MAX_RETRIES      = 3;
    private static final long   INITIAL_INTERVAL = 1_000L;
    private static final double MULTIPLIER       = 2.0;
    private static final long   MAX_INTERVAL     = 10_000L;

    /** Maps a {@link PaymentType} to its canonical routing key on {@link #EXCHANGE}. */
    static String routingKeyFor(PaymentType paymentType) {
        return switch (paymentType) {
            case CREDIT_CARD -> ROUTING_KEY_CREDIT_CARD;
            case INVOICE     -> ROUTING_KEY_INVOICE;
        };
    }

    @Bean
    TopicExchange intakeExchange() {
        return ExchangeBuilder.topicExchange(ENROLLMENT_INTAKE_EXCHANGE).durable(true).build();
    }

    @Bean
    Queue intakeQueue() {
        return QueueBuilder.durable(ENROLLMENT_INTAKE_QUEUE)
                .deadLetterExchange(ENROLLMENT_INTAKE_DLX)
                .deadLetterRoutingKey(ENROLLMENT_INTAKE_DLQ_RK)
                .build();
    }

    @Bean
    DirectExchange intakeDlx() {
        return new DirectExchange(ENROLLMENT_INTAKE_DLX, true, false);
    }

    @Bean
    Queue intakeDlq() {
        return QueueBuilder.durable(ENROLLMENT_INTAKE_DLQ).build();
    }

    @Bean
    Binding intakeDlqBinding(Queue intakeDlq, DirectExchange intakeDlx) {
        return BindingBuilder.bind(intakeDlq).to(intakeDlx).with(ENROLLMENT_INTAKE_DLQ_RK);
    }

    /**
     * Routes every intake publish to the single intake queue. {@code EnrollmentIntakePublisher}
     * uses the same per-payment-type routing keys ({@link #ROUTING_KEY_CREDIT_CARD},
     * {@link #ROUTING_KEY_INVOICE}) on the intake exchange that the events exchange uses,
     * but the intake topology has just one queue — no fan-out — so the binding pattern
     * catches both keys via {@code enrollment.created.*}.
     */
    @Bean
    Binding intakeBinding(Queue intakeQueue, TopicExchange intakeExchange) {
        return BindingBuilder.bind(intakeQueue).to(intakeExchange).with("enrollment.created.*");
    }

    /**
     * Durable topic exchange for the internal scatter-gather pipeline (ADR-003 §Layer 2).
     * Carries {@code EnrollmentAccepted} (intake-fan-out → signal workers) and signal-result
     * events ({@code GeoScoreResult}, {@code FraudCheckResult}). Declared idempotently at startup.
     * {@code EnrollmentDecisionEvent} does NOT flow on this exchange — see {@link #enrollmentDecisionsExchange()}.
     */
    @Bean
    TopicExchange enrollmentExchange() {
        return ExchangeBuilder.topicExchange(EXCHANGE).durable(true).build();
    }

    /**
     * Durable topic exchange for outbound decision events (ADR-003 §Layer 3). Single publisher:
     * {@code EnrollmentDecisionPublisher}. Single consumer: the account service (which owns its
     * own queue and binding — see ADR-003 §"Consumer-owned bindings"). Declared idempotently at
     * startup; the binding from this exchange to the account-service queue is out of scope.
     */
    @Bean
    TopicExchange enrollmentDecisionsExchange() {
        return ExchangeBuilder.topicExchange(DECISION_EXCHANGE).durable(true).build();
    }

    /**
     * Reuses the auto-configured JsonMapper (fail-on-unknown-properties=false via application.yml)
     * for outgoing serialization on the RabbitTemplate.
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
     *       {@code waitForConfirmsOrDie} on the publish path, a lost or nacked broker
     *       confirm surfaces as an exception so the caller can retry.</li>
     *   <li><b>Mandatory + ReturnsCallback</b> — an unroutable message (wrong exchange,
     *       missing binding, typo'd routing key) is returned by the broker rather than
     *       silently dropped. The return is recorded on the per-publish
     *       {@code CorrelationData}; the publisher checks for it after
     *       {@code waitForConfirmsOrDie} and throws.</li>
     *   <li><b>Metrics</b> — nacks and returns increment
     *       {@value #PUBLISH_FAILURE_METRIC} (tagged by {@code reason}) so ops can
     *       alert on publish failures independently of caller-level retries.</li>
     * </ul>
     */
    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                  JacksonJsonMessageConverter messageConverter,
                                  MeterRegistry meterRegistry) {
        var nackCounter = Counter.builder(PUBLISH_FAILURE_METRIC)
                .tag("reason", "nack")
                .description("RabbitMQ publisher confirm nacks for decision-engine events")
                .register(meterRegistry);
        var returnCounter = Counter.builder(PUBLISH_FAILURE_METRIC)
                .tag("reason", "returned")
                .description("Unroutable decision-engine events returned by the broker")
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
        // I/O thread and cannot propagate back to the caller thread.
        template.setReturnsCallback(returned -> {
            log.error("RabbitMQ returned unroutable message exchange={} routingKey={} replyCode={} replyText={}",
                    returned.getExchange(), returned.getRoutingKey(),
                    returned.getReplyCode(), returned.getReplyText());
            returnCounter.increment();
        });

        return template;
    }

    // --- Geo-score result consumer beans ---

    @Bean
    Queue geoScoreQueue() {
        return QueueBuilder.durable(GEO_SCORE_QUEUE)
                .deadLetterExchange(GEO_SCORE_DLX)
                .deadLetterRoutingKey(GEO_SCORE_DLQ_RK)
                .build();
    }

    @Bean
    DirectExchange geoScoreDlx() {
        return new DirectExchange(GEO_SCORE_DLX, true, false);
    }

    @Bean
    Queue geoScoreDlq() {
        return QueueBuilder.durable(GEO_SCORE_DLQ).build();
    }

    @Bean
    Binding geoScoreDlqBinding(Queue geoScoreDlq, DirectExchange geoScoreDlx) {
        return BindingBuilder.bind(geoScoreDlq).to(geoScoreDlx).with(GEO_SCORE_DLQ_RK);
    }

    @Bean
    Binding geoScoreBinding(Queue geoScoreQueue, TopicExchange enrollmentExchange) {
        return BindingBuilder.bind(geoScoreQueue).to(enrollmentExchange).with(GEO_SCORE_ROUTING_KEY);
    }

    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            JacksonJsonMessageConverter messageConverter) {
        var factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setTaskExecutor(new VirtualThreadTaskExecutor("amqp-orch-"));
        factory.setObservationEnabled(true);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .retryPolicy(listenerRetryPolicy())
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build());
        return factory;
    }

    /**
     * Retry policy applied to {@code @RabbitListener} invocations on this factory.
     * Exponential backoff over {@link #MAX_RETRIES} attempts, with one exception
     * type explicitly excluded so it is routed to the DLQ on the first throw
     * instead of consuming the retry budget on a condition that cannot recover:
     * <ul>
     *   <li>{@link UnknownCorrelationException} — a signal result arrived for a
     *       {@code requestId} with no correlation row. Re-invoking the listener
     *       cannot make the row appear; immediate DLQ routing surfaces the
     *       inconsistency for triage instead of delaying it.</li>
     * </ul>
     * Package-private to enable focused unit tests on the retry decisions.
     */
    static RetryPolicy listenerRetryPolicy() {
        return RetryPolicy.builder()
                .maxRetries(MAX_RETRIES)
                .delay(Duration.ofMillis(INITIAL_INTERVAL))
                .multiplier(MULTIPLIER)
                .maxDelay(Duration.ofMillis(MAX_INTERVAL))
                .excludes(UnknownCorrelationException.class)
                .build();
    }
}
