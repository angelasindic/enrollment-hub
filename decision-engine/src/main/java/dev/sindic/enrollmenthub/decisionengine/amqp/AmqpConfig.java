package dev.sindic.enrollmenthub.decisionengine.amqp;

import dev.sindic.enrollmenthub.decisionengine.service.SignalShapeMismatchException;
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
import org.springframework.amqp.support.converter.DefaultJacksonJavaTypeMapper;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConversionException;
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
 * AMQP topology and publisher configuration for the decision-engine (ADR-13;
 * decision-engine/design.md §Exchange and queue topology).
 *
 * <p>Four exchanges: intake, the per-signal request and result pair, and outbound decisions. The
 * decision-engine owns all of them, plus every queue except the account service's decision queue
 * (ADR-13 §Channel Ownership) — workers attach a listener to a request queue by name rather than
 * redeclaring it.
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

    // --- Outbound decisions topology (decision-engine/design.md §Exchange and queue topology) ---

    public static final String DECISION_EXCHANGE    = "enrollment.decisions";
    public static final String DECISION_ROUTING_KEY = "enrollment.decision.completed";

    static final String PUBLISH_FAILURE_METRIC = "decisionengine_publish_failures_total";

    // --- Per-signal scatter-gather topology ---
    // Two direct exchanges, owned by the decision-engine: requests out, results back.
    // Routing key is the signal name, so adding a signal is a queue + binding, not a new exchange.

    public static final String CHECK_REQUEST_EXCHANGE = "enrollment.check.request";
    public static final String CHECK_RESULT_EXCHANGE  = "enrollment.check.result";

    public static final String GEO_SCORE_KEY   = "geo.score";
    public static final String FRAUD_CHECK_KEY = "fraud.check";

    static final String GEO_SCORE_REQUEST_QUEUE   = "geo.scoring.requests.queue";
    static final String FRAUD_CHECK_REQUEST_QUEUE = "fraud.detection.requests.queue";
    static final String GEO_SCORE_RESULT_QUEUE    = "decision-engine.geo-score.results.queue";
    static final String FRAUD_CHECK_RESULT_QUEUE  = "decision-engine.fraud-check.results.queue";
    static final String GEO_SCORE_RESULT_DLQ      = GEO_SCORE_RESULT_QUEUE + ".dlq";
    static final String FRAUD_CHECK_RESULT_DLQ    = FRAUD_CHECK_RESULT_QUEUE + ".dlq";
    static final String GEO_SCORE_REQUEST_DLQ     = GEO_SCORE_REQUEST_QUEUE + ".dlq";
    static final String FRAUD_CHECK_REQUEST_DLQ   = FRAUD_CHECK_REQUEST_QUEUE + ".dlq";

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
     * Durable topic exchange for outbound decision events. Sole publisher is
     * {@code EnrollmentDecisionPublisher}; the account service owns its queue and its binding to
     * this exchange (ADR-13 §Channel Ownership), so neither is declared here.
     */
    @Bean
    TopicExchange enrollmentDecisionsExchange() {
        return ExchangeBuilder.topicExchange(DECISION_EXCHANGE).durable(true).build();
    }

    /**
     * Reuses the auto-configured JsonMapper (fail-on-unknown-properties=false via application.yml)
     * so wire serialization matches the rest of the app.
     */
    @Bean
    JacksonJsonMessageConverter messageConverter(JsonMapper jsonMapper) {
        JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter(jsonMapper);
        // Header-based (__TypeId__) deserialization — e.g. RabbitTemplate.receiveAndConvert in the ITs —
        // must trust our contracts packages; the converter's default trusts only java.util / java.lang.
        // Trusted-package matching is EXACT (no '.*' wildcard; only "*" = trust all), so list them.
        DefaultJacksonJavaTypeMapper typeMapper = new DefaultJacksonJavaTypeMapper();
        typeMapper.setTrustedPackages(
                "dev.sindic.enrollmenthub.contracts.events",
                "dev.sindic.enrollmenthub.contracts.domain",
                // EnrollmentEvent, the decision-engine's own intake envelope
                "dev.sindic.enrollmenthub.decisionengine.amqp");
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }

    /**
     * RabbitTemplate wired so that no publish can fail silently (ADR-13):
     *
     * <ul>
     *   <li><b>Confirms</b> — with {@code waitForConfirmsOrDie} on the publish path, a nacked or
     *       lost confirm surfaces as an exception the caller can retry.</li>
     *   <li><b>Mandatory + ReturnsCallback</b> — an unroutable message is returned rather than
     *       dropped. The return lands on the per-publish {@code CorrelationData}, which the
     *       publisher inspects after the confirm, because this callback runs on the AMQP I/O
     *       thread and cannot throw back to the caller.</li>
     *   <li><b>Metrics</b> — nacks and returns increment {@value #PUBLISH_FAILURE_METRIC}, tagged
     *       by {@code reason}, so ops alerts are independent of caller-level retries.</li>
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
        // Producer-side observation: injects the W3C traceparent into published messages so the
        // consuming service continues the same trace (the listener factory already enables it).
        template.setObservationEnabled(true);

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

    // --- Per-signal scatter-gather topology beans (decision-engine/design.md §Exchange and queue topology) ---

    /**
     * Declares both direct exchanges and the four per-signal queues, each with a dedicated
     * DLX/DLQ. Workers consume the request queues; the decision-engine's own listeners consume
     * the result queues.
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
     * direct DLX/DLQ. Naming convention (decision-engine/design.md §Dead-letter topology): {@code {base}.dlx},
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
     * Exponential backoff over {@link #MAX_RETRIES} attempts for {@code @RabbitListener}
     * invocations, excluding the three failures a redelivery cannot fix:
     * {@link UnknownCorrelationException} (a result for an {@code enrollmentId} with no correlation
     * row), {@link MessageConversionException} (bytes that are not a valid message of the expected
     * type — a contract's own constructor rejecting them surfaces here, wrapped), and
     * {@link SignalShapeMismatchException} (a signal reporting a state its classification cannot
     * use, which is a wiring fault rather than a message fault). All three go to the DLQ on the
     * first throw, surfacing the inconsistency for triage instead of spending the retry budget.
     * Package-private for focused unit tests.
     */
    static RetryPolicy listenerRetryPolicy() {
        return RetryPolicy.builder()
                .maxRetries(MAX_RETRIES)
                .delay(Duration.ofMillis(INITIAL_INTERVAL))
                .multiplier(MULTIPLIER)
                .maxDelay(Duration.ofMillis(MAX_INTERVAL))
                .excludes(UnknownCorrelationException.class,
                          MessageConversionException.class,
                          SignalShapeMismatchException.class)
                .build();
    }
}
