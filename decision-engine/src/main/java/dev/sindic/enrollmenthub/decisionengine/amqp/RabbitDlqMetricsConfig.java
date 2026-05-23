package dev.sindic.enrollmenthub.decisionengine.amqp;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Configuration
public class RabbitDlqMetricsConfig {

    private static final List<DlqGaugeDef> DLQ_GAUGES = List.of(
            new DlqGaugeDef("rabbitmq.dlq.depth",
                    AmqpConfig.GEO_SCORE_DLQ,
                    "Messages waiting in the dead-letter queue from the decision-engine: " +
                            AmqpConfig.GEO_SCORE_DLQ),
            new DlqGaugeDef("rabbitmq.dlq.depth",
                    AmqpConfig.ENROLLMENT_INTAKE_DLQ,
                    "Messages waiting in the dead-letter queue from the decision-engine: " +
                            AmqpConfig.ENROLLMENT_INTAKE_DLQ)
    );

    @Bean
    public List<Gauge> dlqDepthGauges(RabbitTemplate rabbitTemplate, MeterRegistry registry) {
        List<Gauge> registered = new ArrayList<>();

        for (DlqGaugeDef def : DLQ_GAUGES) {
            var gauge = Gauge.builder(def.metricName(), rabbitTemplate, tpl -> {
                try {
                    var ok = tpl.execute(ch -> ch.queueDeclarePassive(def.queueName()));
                    return ok != null ? (double) ok.getMessageCount() : 0.0;
                } catch (Exception e) {
                    log.warn("Could not poll DLQ depth queue={}", def.queueName(), e);
                    return 0.0;
                }
            }).tag("queue", def.queueName()).description(def.description()).register(registry);

            registered.add(gauge);
        }
        return registered;
    }

    private record DlqGaugeDef(String metricName, String queueName, String description) { }
}