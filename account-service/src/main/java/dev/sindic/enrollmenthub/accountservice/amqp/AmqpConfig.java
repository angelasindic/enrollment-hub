package dev.sindic.enrollmenthub.accountservice.amqp;

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
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.DefaultJacksonJavaTypeMapper;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumer-side topology for decision events (ADR-13 §Channel Ownership). The decision-engine owns
 * the {@code enrollment.decisions} exchange; the account service owns the queue that receives from
 * it, the binding, and the DLX/DLQ. Declaring the queue here is what makes the decision-engine's
 * mandatory publish routable, so the account service has to have started once against a broker
 * before decisions can be dispatched. The queue is durable, so decisions published while this
 * service is down wait on it.
 */
@Configuration
class AmqpConfig {

    // Exchange and routing key — owned by the decision-engine; also declared there.
    static final String DECISION_EXCHANGE    = "enrollment.decisions";
    static final String DECISION_ROUTING_KEY = "enrollment.decision.completed";

    static final String DECISION_QUEUE  = "account-service.decisions.queue";
    static final String DECISION_DLX    = "account-service.decisions.dlx";
    static final String DECISION_DLQ    = DECISION_QUEUE + ".dlq";
    static final String DECISION_DLQ_RK = "account-service.decisions.dead-letter";

    private static final int    MAX_RETRIES      = 3;
    private static final long   INITIAL_INTERVAL = 1_000L;
    private static final double MULTIPLIER       = 2.0;
    private static final long   MAX_INTERVAL     = 10_000L;

    /** Decisions exchange (shared; also declared by the decision-engine). Declared idempotently. */
    @Bean
    TopicExchange decisionExchange() {
        return ExchangeBuilder.topicExchange(DECISION_EXCHANGE).durable(true).build();
    }

    @Bean
    Queue decisionQueue() {
        return QueueBuilder.durable(DECISION_QUEUE)
                .deadLetterExchange(DECISION_DLX)
                .deadLetterRoutingKey(DECISION_DLQ_RK)
                .build();
    }

    @Bean
    Binding decisionBinding(Queue decisionQueue, TopicExchange decisionExchange) {
        return BindingBuilder.bind(decisionQueue).to(decisionExchange).with(DECISION_ROUTING_KEY);
    }

    @Bean
    DirectExchange decisionDlx() {
        return new DirectExchange(DECISION_DLX, true, false);
    }

    @Bean
    Queue decisionDlq() {
        return QueueBuilder.durable(DECISION_DLQ).build();
    }

    @Bean
    Binding decisionDlqBinding(Queue decisionDlq, DirectExchange decisionDlx) {
        return BindingBuilder.bind(decisionDlq).to(decisionDlx).with(DECISION_DLQ_RK);
    }

    /**
     * Reuses the auto-configured JsonMapper (fail-on-unknown-properties=false via application.yml)
     * so a field the decision-engine adds to the contract does not break deserialization here.
     */
    @Bean
    JacksonJsonMessageConverter messageConverter(JsonMapper jsonMapper) {
        JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter(jsonMapper);
        // Header-based (__TypeId__) deserialization must trust our contracts packages; the
        // converter's default trusts only java.util / java.lang. Matching is exact, so list them.
        DefaultJacksonJavaTypeMapper typeMapper = new DefaultJacksonJavaTypeMapper();
        typeMapper.setTrustedPackages(
                "dev.sindic.enrollmenthub.contracts.events",
                "dev.sindic.enrollmenthub.contracts.domain");
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }

    /**
     * Listener container factory with a virtual-thread executor (ADR-04, {@code amqp-account-}
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
        factory.setTaskExecutor(new VirtualThreadTaskExecutor("amqp-account-"));
        factory.setObservationEnabled(true);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .maxRetries(MAX_RETRIES)
                .backOffOptions(INITIAL_INTERVAL, MULTIPLIER, MAX_INTERVAL)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build());
        return factory;
    }
}
