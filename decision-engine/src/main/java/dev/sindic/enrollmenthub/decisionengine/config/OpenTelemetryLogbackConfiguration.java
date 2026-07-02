package dev.sindic.enrollmenthub.decisionengine.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.context.annotation.Configuration;

/**
 * Installs the auto-configured {@link OpenTelemetry} SDK into the Logback {@code OpenTelemetryAppender}.
 *
 * <p>Spring Boot's {@code spring-boot-opentelemetry} builds the SDK {@code LoggerProvider} and the OTLP
 * log-record exporter, but it ships no Logback integration and never calls
 * {@link OpenTelemetryAppender#install(OpenTelemetry)}. Without this, the {@code OTEL} appender declared
 * in {@code logback-spring.xml} captures log events but has no SDK to forward them to, so no logs reach
 * the collector (and Loki stays empty) even though traces export fine. The constructor runs once the
 * {@code OpenTelemetry} bean is ready; the appender buffers the few events emitted before then.
 */
@Configuration
class OpenTelemetryLogbackConfiguration {

    OpenTelemetryLogbackConfiguration(OpenTelemetry openTelemetry) {
        OpenTelemetryAppender.install(openTelemetry);
    }
}
