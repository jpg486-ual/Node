package es.ual.node.recovery.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import es.ual.node.bootstrap.configuration.NodeTopologyProperties;
import es.ual.node.recovery.adapters.out.memory.InMemoryRecoveryOrphanFragmentPayloadPort;
import es.ual.node.recovery.adapters.out.memory.InMemoryRecoveryOrphanFragmentPort;
import es.ual.node.reedsolomon.adapters.out.memory.InMemoryRsCodecAdapter;
import es.ual.node.reedsolomon.adapters.out.memory.InMemoryRsIntegrityVerifier;
import es.ual.node.reedsolomon.domain.RsFragment;
import es.ual.node.reedsolomon.domain.RsScheme;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Domain spans manuales en {@link TutorRecoveryService}: {@code node.recovery.reconstruct} (parent)
 * y {@code node.reedsolomon.decode} (child del reconstruct cuando se llama a {@code reconstruct}).
 */
class TutorRecoveryServiceTracingTest {

  private OpenTelemetrySdk otelSdk;
  private InMemorySpanExporter spanExporter;
  private TutorRecoveryService service;

  @BeforeEach
  void setUp() {
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

    final NodeTopologyProperties topologyProperties = new NodeTopologyProperties();
    topologyProperties.setTutorAcceptedPublicKeys(List.of("allowed-key"));
    final Clock clock = Clock.fixed(Instant.parse("2026-04-25T10:00:00Z"), ZoneOffset.UTC);

    service =
        new TutorRecoveryService(
            topologyProperties,
            new InMemoryRecoveryOrphanFragmentPort(),
            new InMemoryRecoveryOrphanFragmentPayloadPort(),
            new InMemoryRsCodecAdapter(),
            new InMemoryRsIntegrityVerifier(),
            clock,
            RecoveryObservabilityService.noop(),
            registry);
  }

  @AfterEach
  void tearDown() {
    if (otelSdk != null) {
      otelSdk.close();
    }
  }

  @Test
  void reconstructEmitsParentChildSpansRecoveryReconstructAndReedsolomonDecode() {
    final byte[] originalPayload = "reed-solomon-recovery".getBytes(StandardCharsets.UTF_8);
    final String originalHash = sha256Hex(originalPayload);
    final RsScheme scheme = new RsScheme(6, 4, 8);
    final InMemoryRsCodecAdapter codec = new InMemoryRsCodecAdapter();
    final List<RsFragment> encodedFragments = codec.encode(originalPayload, scheme);

    // Store sufficient fragments (k=4) under custody
    final List<RsFragment> available =
        List.of(
            encodedFragments.get(0),
            encodedFragments.get(1),
            encodedFragments.get(3),
            encodedFragments.get(5));
    for (RsFragment fragment : available) {
      service.store(
          new TutorRecoveryService.StoreRecoveryFragmentRequest(
              fragment.fragmentId(),
              "agreement-rs",
              "node-a",
              "allowed-key",
              "SHA-256",
              fragment.checksum(),
              Base64.getEncoder().encodeToString(fragment.payload())));
    }

    spanExporter.reset();

    service.reconstruct(
        new TutorRecoveryService.ReconstructRecoveryFragmentsRequest(
            "file-rs",
            originalHash,
            scheme.n(),
            scheme.k(),
            scheme.symbolSize(),
            available.stream()
                .map(
                    fragment ->
                        new TutorRecoveryService.ReconstructFragmentReference(
                            fragment.fragmentId(), fragment.index(), fragment.isParity()))
                .toList()));

    final List<SpanData> spans = spanExporter.getFinishedSpanItems();

    final SpanData reconstructSpan =
        spans.stream()
            .filter(s -> "node.recovery.reconstruct".equals(s.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected node.recovery.reconstruct"));
    final SpanData decodeSpan =
        spans.stream()
            .filter(s -> "node.reedsolomon.decode".equals(s.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected node.reedsolomon.decode"));

    // Parent-child: decode lleva el traceId del reconstruct y como parent al reconstruct span
    assertEquals(reconstructSpan.getTraceId(), decodeSpan.getTraceId());
    assertEquals(reconstructSpan.getSpanId(), decodeSpan.getParentSpanId());

    assertEquals("4", attribute(reconstructSpan, "fragment.count"));
    assertEquals("6", attribute(decodeSpan, "scheme.n"));
    assertEquals("4", attribute(decodeSpan, "scheme.k"));
    assertEquals("4", attribute(decodeSpan, "available.shards"));

    assertNoForbiddenTags(reconstructSpan);
    assertNoForbiddenTags(decodeSpan);
    assertNotNull(reconstructSpan.getSpanId());
  }

  private static String attribute(final SpanData span, final String key) {
    return span.getAttributes().asMap().entrySet().stream()
        .filter(e -> key.equals(e.getKey().getKey()))
        .map(e -> String.valueOf(e.getValue()))
        .findFirst()
        .orElse(null);
  }

  private static void assertNoForbiddenTags(final SpanData span) {
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

  private static String sha256Hex(final byte[] payload) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      final byte[] hash = digest.digest(payload);
      final StringBuilder builder = new StringBuilder(hash.length * 2);
      for (byte value : hash) {
        builder.append(String.format("%02x", value));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 not available", ex);
    }
  }
}
