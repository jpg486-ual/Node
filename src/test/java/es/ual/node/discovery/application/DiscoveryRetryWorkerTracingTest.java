package es.ual.node.discovery.application;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import es.ual.node.discovery.adapters.out.memory.InMemoryDiscoveryCandidateDirectoryAdapter;
import es.ual.node.discovery.adapters.out.memory.InMemoryDiscoveryRetryQueuePort;
import es.ual.node.discovery.domain.DiscoveryRequest;
import es.ual.node.identitysecurity.adapters.out.memory.InMemoryPublicKeyRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Domain span manual {@code node.discovery.retry.cycle} en {@link DiscoveryRetryWorker}. */
class DiscoveryRetryWorkerTracingTest {

  private OpenTelemetrySdk otelSdk;
  private InMemorySpanExporter spanExporter;
  private DiscoveryRetryWorker worker;

  @BeforeEach
  void setUp() throws Exception {
    spanExporter = InMemorySpanExporter.create();
    final SdkTracerProvider tracerProvider =
        SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
            .build();
    otelSdk =
        OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();

    final Tracer tracer =
        new OtelTracer(otelSdk.getTracer("test"), new OtelCurrentTraceContext(), event -> {});
    final ObservationRegistry registry = ObservationRegistry.create();
    registry.observationConfig().observationHandler(new DefaultTracingObservationHandler(tracer));

    final Clock clock = Clock.fixed(Instant.parse("2026-04-25T10:00:00Z"), ZoneOffset.UTC);

    final InMemoryPublicKeyRegistry pkRegistry = new InMemoryPublicKeyRegistry();
    final KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC");
    keyPairGenerator.initialize(256);
    pkRegistry.register("requester", keyPairGenerator.generateKeyPair().getPublic());

    final InMemoryDiscoveryCandidateDirectoryAdapter directory =
        new InMemoryDiscoveryCandidateDirectoryAdapter();
    final DiscoveryProperties discoveryProperties = new DiscoveryProperties();
    discoveryProperties.setFailureDomainFilterEnabled(false);
    final DiscoveryService discoveryService =
        new DiscoveryService(pkRegistry, directory, discoveryProperties);

    final DiscoveryRetryProperties retryProperties = new DiscoveryRetryProperties();
    retryProperties.setBaseDelaySeconds(5);
    retryProperties.setMaxDelaySeconds(30);
    retryProperties.setBatchSize(10);

    final DiscoveryRetryQueueService queueService =
        new DiscoveryRetryQueueService(
            new InMemoryDiscoveryRetryQueuePort(), retryProperties, clock);
    queueService.enqueue(new DiscoveryRequest("requester", "zone-a", 1024L, 1.0d, 10));

    worker = new DiscoveryRetryWorker(discoveryService, queueService, registry);
  }

  @AfterEach
  void tearDown() {
    if (otelSdk != null) {
      otelSdk.close();
    }
  }

  @Test
  void processDueEmitsNodeDiscoveryRetryCycleSpan() {
    worker.processDue();

    final List<SpanData> spans = spanExporter.getFinishedSpanItems();
    final SpanData cycleSpan =
        spans.stream()
            .filter(s -> "node.discovery.retry.cycle".equals(s.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected span node.discovery.retry.cycle"));
    assertNotNull(cycleSpan.getSpanId());
    span_assertNoForbiddenTags(cycleSpan);
  }

  private static void span_assertNoForbiddenTags(final SpanData span) {
    span.getAttributes()
        .asMap()
        .keySet()
        .forEach(
            k -> {
              final String key = k.getKey();
              assertTrue(
                  !key.contains("password")
                      && !key.contains("token")
                      && !key.contains("signature")
                      && !key.contains("payload")
                      && !key.contains("secret"),
                  "forbidden tag found: " + key);
            });
  }
}
