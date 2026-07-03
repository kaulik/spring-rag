package com.example.rag.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.resources.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class OtelLoggingConfig {

    // No fallback on purpose — the deployment must supply the endpoint explicitly.
    @Value("${OTEL_EXPORTER_OTLP_ENDPOINT}")
    private String otlpEndpoint;

    /**
     * Spring Boot's OpenTelemetryAutoConfiguration picks up this bean and
     * includes it in the OpenTelemetry SDK instance — enabling OTLP log export.
     * The injected Resource is Boot's auto-configured one, carrying service.name
     * (from spring.application.name) plus OTEL_RESOURCE_ATTRIBUTES such as
     * service.instance.id — without it, exported logs show unknown_service:java.
     */
    @Bean
    public SdkLoggerProvider sdkLoggerProvider(Resource resource) {
        String logsUrl = otlpEndpoint.replaceAll("/+$", "") + "/v1/logs";
        log.info("Configuring OTLP log exporter → {}", logsUrl);
        OtlpHttpLogRecordExporter exporter = OtlpHttpLogRecordExporter.builder()
                .setEndpoint(logsUrl)
                .build();
        return SdkLoggerProvider.builder()
                .setResource(resource)
                .addLogRecordProcessor(BatchLogRecordProcessor.builder(exporter).build())
                .build();
    }

    /**
     * Installs the OpenTelemetryAppender with the fully-configured OpenTelemetry
     * instance (including the SdkLoggerProvider above) once the context is ready.
     */
    @Bean
    ApplicationRunner installOtelLogAppender(OpenTelemetry openTelemetry) {
        return args -> {
            OpenTelemetryAppender.install(openTelemetry);
            log.info("OpenTelemetry logback appender installed — logs will be pushed via OTLP");
        };
    }
}
