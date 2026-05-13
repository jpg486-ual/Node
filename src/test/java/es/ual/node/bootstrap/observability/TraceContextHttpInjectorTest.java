package es.ual.node.bootstrap.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.extension.trace.propagation.B3Propagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link TraceContextHttpInjector}. */
class TraceContextHttpInjectorTest {

  private OpenTelemetrySdk otelSdk;

  @AfterEach
  void tearDown() {
    if (otelSdk != null) {
      otelSdk.close();
    }
  }

  @Test
  void injectsTraceparentHeaderWhenSpanIsActive() {
    final OpenTelemetry openTelemetry = otelWith(W3CTraceContextPropagator.getInstance());
    final TraceContextHttpInjector injector = new TraceContextHttpInjector(openTelemetry);
    final Tracer tracer = openTelemetry.getTracer("test");
    final Span span = tracer.spanBuilder("test-span").startSpan();
    try (var scope = span.makeCurrent()) {
      final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://example/"));
      injector.inject(builder);
      final HttpRequest request = builder.GET().build();

      final List<String> traceparent =
          request.headers().map().getOrDefault("traceparent", List.of());
      assertEquals(1, traceparent.size(), "exactamente un header traceparent");

      // Format: version-trace_id-span_id-flags  (00-32hex-16hex-XX)
      final String header = traceparent.get(0);
      assertTrue(
          header.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}"),
          "traceparent debe seguir formato W3C: " + header);
      assertTrue(
          header.contains(span.getSpanContext().getTraceId()),
          "traceparent debe contener el trace-id del span activo");
      assertTrue(
          header.contains(span.getSpanContext().getSpanId()),
          "traceparent debe contener el span-id del span activo");
    } finally {
      span.end();
    }
  }

  @Test
  void doesNotInjectAnyHeaderWhenNoSpanActive() {
    final OpenTelemetry openTelemetry = otelWith(W3CTraceContextPropagator.getInstance());
    final TraceContextHttpInjector injector = new TraceContextHttpInjector(openTelemetry);
    // Nota: Context.current() devuelve el root context cuando no hay span.

    final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://example/"));
    injector.inject(builder);
    final HttpRequest request = builder.GET().build();

    assertTrue(
        request.headers().map().getOrDefault("traceparent", List.of()).isEmpty(),
        "no debe haber traceparent fuera de scope de span");
  }

  @Test
  void respectsConfiguredPropagatorWhenNotW3c() {
    // Si el OpenTelemetry expuesto por Spring Boot estuviera configurado con B3 multi-header,
    // el helper debe usarlo igualmente. Aqui forzamos B3 y verificamos que el helper inyecta
    // X-B3-TraceId / X-B3-SpanId en lugar de traceparent.
    final OpenTelemetry openTelemetry = otelWith(B3Propagator.injectingMultiHeaders());
    final TraceContextHttpInjector injector = new TraceContextHttpInjector(openTelemetry);
    final Tracer tracer = openTelemetry.getTracer("test");
    final Span span = tracer.spanBuilder("b3-span").startSpan();
    try (var scope = span.makeCurrent()) {
      final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://example/"));
      injector.inject(builder);
      final HttpRequest request = builder.GET().build();

      assertTrue(
          request.headers().map().containsKey("X-B3-TraceId"),
          "B3 multi-header propagator debe haber inyectado X-B3-TraceId");
      assertTrue(
          request.headers().map().getOrDefault("traceparent", List.of()).isEmpty(),
          "B3 puro no debe inyectar traceparent");
    } finally {
      span.end();
    }
  }

  @Test
  void rejectsNullOpenTelemetry() {
    assertThrows(IllegalArgumentException.class, () -> new TraceContextHttpInjector(null));
  }

  @Test
  void rejectsNullBuilder() {
    final OpenTelemetry openTelemetry = otelWith(W3CTraceContextPropagator.getInstance());
    final TraceContextHttpInjector injector = new TraceContextHttpInjector(openTelemetry);
    assertThrows(IllegalArgumentException.class, () -> injector.inject(null));
  }

  private OpenTelemetry otelWith(final TextMapPropagator propagator) {
    final SdkTracerProvider tracerProvider =
        SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(InMemorySpanExporter.create()))
            .build();
    otelSdk =
        OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(ContextPropagators.create(propagator))
            .build();
    return otelSdk;
  }
}
