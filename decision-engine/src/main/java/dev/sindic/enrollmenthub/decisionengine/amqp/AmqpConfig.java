package dev.sindic.enrollmenthub.decisionengine.amqp;

import dev.sindic.enrollmenthub.decisionengine.service.UnknownCorrelationException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
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
import java.util.ArrayList;
import java.util.List;

/**
 * AMQP topology and publisher configuration for the decision-engine.
 *
 * <h2>Per-signal scatter-gather (ADR-003 §Layer 2)</h2>
 * The decision-engine dispatches one command per applicable signal to the
 * {@link #CHECK_REQUEST_EXCHANGE} direct exchange (routing key = signal name, e.g.
 * {@link #GEO_SCORE_KEY} / {@link #FRAUD_CHECK_KEY}) and gathers replies from the
 * {@link #CHECK_RESULT_EXCHANGE}. It owns both exchanges and the per-signal request and result
 * queues (declared in {@link #checkChannelTopology()}); worker services attach a listener to a
 * request queue by name without redeclaring it.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(AmqpProperties.class)
public class AmqpConfig {


    public static final String ENROLLMENT_INTAKE_EXCHANGE         = "enrollment.intake";
    public static final String ENROLLMENT_INTAKE_ROUTING_KEY     = "enrollment.intake";
    public static final String ENROLLMENT_INTAKE_QUEUE            = "enrollment.intake.queue";
    static final String ENROLLMENT_INTAKE_DLX         = "enrollment.intake.dlx";
    static final String ENROLLMENT_INTAKE_DLQ         = "enrollment.intake.queue.dlq";
    static final String ENROLLMENT_INTAKE_DLQ_RK      = "enrollment.intake.dead-letter";

    // --- Outbound decisions topology (ADR-003 §Layer 3) ---

    public static final String DECISION_EXCHANGE    = "enrollment.decisions";
    public static final String DECISION_ROUTING_KEY = "enrollment.decision.completed";

    static final String PUBLISH_FAILURE_METRIC = "decisionengine_publish_failures_total";

    // --- Per-signal scatter-gather topology (ADR-003 §Layer 2) ---
    // Two direct exchanges, owned by the decision-engine: requests out, results back.
    // Declared here so the cut-over change can wire dispatch + listeners; nothing
    // publishes to or consumes from these yet.

    public static final String CHECK_REQUEST_EXCHANGE = "enrollment.check.request";
    public static final String CHECK_RESULT_EXCHANGE  = "enrollment.check.result";

    public static final String GEO_SCORE_KEY   = "geo.score";
    public static final String FRAUD_CHECK_KEY = "fraud.check";

    static final String GEO_SCORE_REQUEST_QUEUE   = "geo.scoring.requests.queue";
    static final String FRAUD_CHECK_REQUEST_QUEUE = "fraud.detection.requests.queue";
    static final String GEO_SCORE_RESULT_QUEUE    = "decision-engine.geo-score.results.queue";
    static final String FRAUD_CHECK_RESULT_QUEUE  = "decision-engine.fraud-check.results.queue";
    static final String GEO_SCORE_RESULT_DLQ      = GEO_SCORE_RESULT_QUEUE + ".dlq";

    private static final int    MAX_RETRIES      = 3;
    private static final long   INITIAL_INTERVAL = 1_000L;
    private static final double MULTIPLIER       = 2.0;
    private static final long   MAX_INTERVAL     = 10_000L;

    @Bean
    DirectExchange intakeExchange() {
        return ExchangeBuilder.directExchange(ENROLLMENT_INTAKE_EXCHANGE).durable(true).build();
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
     * Point-to-point binding: every intake publish lands on the single intake queue.
     * Payment-type differentiation is the concern of Layer 2 (the check request exchange);
     * the intake exchange only needs to route durably to one queue.
     */
    @Bean
    Binding intakeBinding(Queue intakeQueue, DirectExchange intakeExchange) {
        return BindingBuilder.bind(intakeQueue).to(intakeExchange).with(ENROLLMENT_INTAKE_ROUTING_KEY);
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

    // --- Per-signal scatter-gather topology beans (ADR-003 §Layer 2) ---

    /**
     * Declares the request and result direct exchanges and the decision-engine-owned per-signal
     * queues (each with a dedicated DLX/DLQ). Workers bind a listener to a request queue by name
     * in the cut-over change; result queues are consumed by the decision-engine's own listeners.
     */
    @Bean
    Declarables checkChannelTopology() {
        var requestExchange = new DirectExchange(CHECK_REQUEST_EXCHANGE, true, false);
        var resultExchange  = new DirectExchange(CHECK_RESULT_EXCHANGE, true, false);

        var declarables = new ArrayList<Declarable>();
        declarables.add(requestExchange);
        declarables.add(resultExchange);
        declarables.addAll(deadLetteredQueue(GEO_SCORE_REQUEST_QUEUE,   requestExchange, GEO_SCORE_KEY));
        declarables.addAll(deadLetteredQueue(FRAUD_CHECK_REQUEST_QUEUE, requestExchange, FRAUD_CHECK_KEY));
        declarables.addAll(deadLetteredQueue(GEO_SCORE_RESULT_QUEUE,    resultExchange,  GEO_SCORE_KEY));
        declarables.addAll(deadLetteredQueue(FRAUD_CHECK_RESULT_QUEUE,  resultExchange,  FRAUD_CHECK_KEY));
        return new Declarables(declarables);
    }

    /**
     * A durable queue bound to {@code exchange} on {@code routingKey}, paired with a dedicated
     * direct DLX/DLQ. Naming convention (ADR-003 §Dead-letter topology): {@code {base}.dlx},
     * {@code {queue}.dlq}, dead-letter routing key {@code {base}.dead-letter}, where {@code base}
     * is the queue name without its trailing {@code .queue}.
     */
    private static List<Declarable> deadLetteredQueue(String queueName, DirectExchange exchange, String routingKey) {
        var base = queueName.endsWith(".queue") ? queueName.substring(0, queueName.length() - ".queue".length()) : queueName;
        var dlxName = base + ".dlx";
        var dlqName = queueName + ".dlq";
        var dlqRoutingKey = base + ".dead-letter";

        var queue = QueueBuilder.durable(queueName)
                .deadLetterExchange(dlxName)
                .deadLetterRoutingKey(dlqRoutingKey)
                .build();
        var dlx = new DirectExchange(dlxName, true, false);
        var dlq = QueueBuilder.durable(dlqName).build();
        return List.of(
                queue, dlx, dlq,
                BindingBuilder.bind(dlq).to(dlx).with(dlqRoutingKey),
                BindingBuilder.bind(queue).to(exchange).with(routingKey));
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
     *       {@code enrollmentId} with no correlation row. Re-invoking the listener
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
