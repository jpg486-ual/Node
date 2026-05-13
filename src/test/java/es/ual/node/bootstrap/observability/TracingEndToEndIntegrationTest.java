package es.ual.node.bootstrap.observability;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import es.ual.node.bootstrap.configuration.TestNodeIdentityKeys;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Verifica que la auto-instrumentación server-side de Spring Boot + Micrometer Tracing genera un
 * span por cada request HTTP entrante sin código adicional. Solo con las dependencias {@code
 * micrometer-tracing-bridge-otel} + {@code opentelemetry-exporter-otlp} y {@code
 * management.tracing.enabled=true} en application.properties.
 *
 * <p>Usa {@link InMemorySpanExporter} registrado como {@link SpanProcessor} adicional — coexiste
 * con el exporter OTLP default (que en test simplemente hace drop silente al no tener Tempo
 * arriba). El sampling se fuerza al 100% con {@code management.tracing.sampling.probability=1.0}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = {
      "management.server.port=0",
      "management.endpoints.web.exposure.include=health,info,prometheus",
      "management.tracing.sampling.probability=1.0",
      "management.otlp.tracing.endpoint=http://localhost:4318/v1/traces"
    })
@Import(TracingEndToEndIntegrationTest.TracingTestConfig.class)
class TracingEndToEndIntegrationTest {

  private static final String[] NODE_IDENTITY_PROPERTIES =
      TestNodeIdentityKeys.generatePropertyValues();

  @DynamicPropertySource
  static void configureNodeIdentity(final DynamicPropertyRegistry registry) {
    for (String property : NODE_IDENTITY_PROPERTIES) {
      final int separatorIndex = property.indexOf('=');
      final String key = property.substring(0, separatorIndex);
      final String value = property.substring(separatorIndex + 1);
      registry.add(key, () -> value);
    }
  }

  @Autowired private TestRestTemplate restTemplate;

  @Autowired private InMemorySpanExporter spanExporter;

  @Value("${local.server.port}")
  private int mainPort;

  @Value("${local.management.port}")
  private int managementPort;

  @BeforeEach
  void resetSpans() {
    spanExporter.reset();
  }

  @Test
  void serverSpanIsEmittedForActuatorHealthRequest() {
    restTemplate.getForEntity(
        "http://localhost:" + managementPort + "/actuator/health", String.class);

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertFalse(spanExporter.getFinishedSpanItems().isEmpty()));

    final List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertNotNull(spans);
    final SpanData httpSpan =
        spans.stream()
            .filter(
                s ->
                    s.getName().toLowerCase().contains("health")
                        || s.getName().contains("/actuator")
                        || s.getName().toLowerCase().startsWith("http "))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "ningun span con nombre HTTP/health emitido. Spans observados: "
                            + spans.stream().map(SpanData::getName).toList()));

    assertTrue(
        httpSpan.getName().toLowerCase().contains("health")
            || httpSpan.getName().contains("/actuator")
            || httpSpan.getName().toLowerCase().contains("http"),
        "span name debe referenciar el endpoint HTTP: " + httpSpan.getName());
  }

  @Test
  void emittedSpanCarriesValidW3cFormatTraceId() {
    // Smoke check: la auto-instrumentation produce trace-ids en formato W3C (32 chars hex).
    // La validacion completa de propagacion (traceparent entrante -> server span hereda
    // trace-id; outbound request lleva traceparent con span-id propio) se hace en
    // TraceContextPropagationIntegrationTest (Parte C) con un round-trip real entre adapters.
    restTemplate.getForEntity(
        "http://localhost:" + managementPort + "/actuator/health", String.class);

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertFalse(spanExporter.getFinishedSpanItems().isEmpty()));

    final SpanData span = spanExporter.getFinishedSpanItems().get(0);
    assertEquals(32, span.getTraceId().length(), "trace-id W3C debe tener 32 chars hex");
    assertTrue(
        span.getTraceId().matches("[0-9a-f]{32}"),
        "trace-id debe ser hex puro (formato W3C): " + span.getTraceId());
    assertEquals(16, span.getSpanId().length(), "span-id W3C debe tener 16 chars hex");
  }

  @TestConfiguration
  static class TracingTestConfig {

    @Bean
    InMemorySpanExporter inMemorySpanExporter() {
      return InMemorySpanExporter.create();
    }

    @Bean
    SpanProcessor inMemorySpanProcessor(final InMemorySpanExporter exporter) {
      return SimpleSpanProcessor.create(exporter);
    }
  }
}
