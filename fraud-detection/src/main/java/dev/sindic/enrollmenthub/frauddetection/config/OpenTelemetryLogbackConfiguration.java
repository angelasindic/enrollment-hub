package dev.sindic.enrollmenthub.frauddetection.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.context.annotation.Configuration;

/**
 * Installs the autoconfigured OpenTelemetry SDK into the Logback OpenTelemetryAppender.
 * Spring Boot builds the SDK LoggerProvider + OTLP log exporter but never installs the
 * appender, so without this the OTEL appender captures log events and drops them.
 */
@Configuration
class OpenTelemetryLogbackConfiguration {

    OpenTelemetryLogbackConfiguration(OpenTelemetry openTelemetry) {
        OpenTelemetryAppender.install(openTelemetry);
    }
}
