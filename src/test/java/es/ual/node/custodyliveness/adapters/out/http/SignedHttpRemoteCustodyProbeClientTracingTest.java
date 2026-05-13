package es.ual.node.custodyliveness.adapters.out.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import es.ual.node.bootstrap.configuration.NodeTopologyProperties;
import es.ual.node.bootstrap.observability.TraceContextHttpInjector;
import es.ual.node.custodyliveness.application.CustodyLivenessProperties;
import es.ual.node.custodyliveness.domain.CustodyProbeFragment;
import es.ual.node.custodyliveness.domain.CustodyProbeRequest;
import es.ual.node.identitysecurity.application.NodeIdentityContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Invariantes de instrumentación manual del {@link SignedHttpRemoteCustodyProbeClient}.
 *
 * <ul>
 *   <li>Se emite un span con nombre {@code node.custody.probe.outbound}.
 *   <li>El span lleva tags {@code peer.node.id}, {@code http.url}, {@code http.method}, {@code
 *       http.status_code}.
 *   <li>El outbound HTTP request lleva header {@code traceparent} (W3C) propagando trace-id +
 *       span-id del span activo.
 *   <li>La firma inter-node sigue intacta (el header {@code X-Signature} llega tal cual).
 * </ul>
 */
class SignedHttpRemoteCustodyProbeClientTracingTest {

  private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";

  private HttpServer server;
  private OpenTelemetrySdk otelSdk;
  private InMemorySpanExporter spanExporter;
  private Tracer tracer;
  private TraceContextHttpInjector traceInjector;

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress(0), 0);

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

    tracer = new OtelTracer(otelSdk.getTracer("test"), new OtelCurrentTraceContext(), event -> {});
    traceInjector = new TraceContextHttpInjector(otelSdk);
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
    if (otelSdk != null) {
      otelSdk.close();
    }
  }

  @Test
  void emitsOutboundSpanAndPropagatesTraceparentHeader() throws Exception {
    final AtomicReference<String> traceparentHeader = new AtomicReference<>();
    final AtomicReference<String> signatureHeader = new AtomicReference<>();
    server.createContext(
        "/custody/liveness/probes",
        exchange -> {
          traceparentHeader.set(exchange.getRequestHeaders().getFirst("traceparent"));
          signatureHeader.set(exchange.getRequestHeaders().getFirst("X-Signature"));
          exchange.getRequestBody().readAllBytes();
          final byte[] body =
              successfulProbeResponse("remote-trace-1").getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();

    final String baseUrl = "http://localhost:" + server.getAddress().getPort();
    final SignedHttpRemoteCustodyProbeClient client =
        new SignedHttpRemoteCustodyProbeClient(
            nodeIdentity("node-local"),
            new NodeTopologyProperties(),
            livenessProperties(),
            objectMapper(),
            SIGNATURE_ALGORITHM,
            tracer,
            traceInjector);

    client.probe(baseUrl, probeRequest("req-trace-1"));

    // Header W3C debe haber llegado al servidor.
    assertNotNull(traceparentHeader.get(), "outbound debe llevar header traceparent");
    assertTrue(
        traceparentHeader.get().matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}"),
        "traceparent debe seguir formato W3C: " + traceparentHeader.get());

    // Firma sigue intacta (no la rompimos al inyectar trace).
    assertNotNull(signatureHeader.get(), "X-Signature debe seguir presente");
    assertTrue(
        !signatureHeader.get().isBlank(),
        "X-Signature no debe ser blank tras la instrumentacion de tracing");

    // Span emitido con nombre + tags esperados.
    final List<SpanData> spans = spanExporter.getFinishedSpanItems();
    final SpanData clientSpan =
        spans.stream()
            .filter(s -> "node.custody.probe.outbound".equals(s.getName()))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "no span 'node.custody.probe.outbound'. Spans observados: "
                            + spans.stream().map(SpanData::getName).toList()));

    // El test usa baseUrl como remoteNodeId (patron del test funcional existente),
    // por lo que peer.node.id refleja el URL del peer.
    assertEquals(baseUrl, attributeValue(clientSpan, "peer.node.id"));
    assertEquals(baseUrl + "/custody/liveness/probes", attributeValue(clientSpan, "http.url"));
    assertEquals("POST", attributeValue(clientSpan, "http.method"));
    assertEquals("200", attributeValue(clientSpan, "http.status_code"));

    // El traceparent enviado debe corresponder al span emitido.
    assertTrue(
        traceparentHeader.get().contains(clientSpan.getTraceId()),
        "traceparent debe contener el trace-id del span outbound emitido");
    assertTrue(
        traceparentHeader.get().contains(clientSpan.getSpanId()),
        "traceparent debe contener el span-id del span outbound emitido");
  }

  private static String attributeValue(final SpanData span, final String key) {
    return span.getAttributes().asMap().entrySet().stream()
        .filter(e -> e.getKey().getKey().equals(key))
        .map(e -> String.valueOf(e.getValue()))
        .findFirst()
        .orElse(null);
  }

  private static NodeIdentityContext nodeIdentity(final String nodeId) throws Exception {
    final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(256);
    final KeyPair keyPair = generator.generateKeyPair();
    return new NodeIdentityContext(nodeId, keyPair.getPublic(), keyPair.getPrivate());
  }

  private static CustodyLivenessProperties livenessProperties() {
    final CustodyLivenessProperties properties = new CustodyLivenessProperties();
    properties.setRequestTimeoutMillis(2000L);
    return properties;
  }

  private static ObjectMapper objectMapper() {
    return new ObjectMapper().registerModule(new JavaTimeModule());
  }

  private static CustodyProbeRequest probeRequest(final String requestId) {
    return CustodyProbeRequest.withoutRequesterTutor(
        requestId,
        "node-local",
        "node-local",
        List.of(new CustodyProbeFragment("frag-1", "agreement-1", "checksum-1", 100L)),
        Instant.parse("2026-04-25T10:00:00Z"),
        60L);
  }

  private static String successfulProbeResponse(final String responseId) {
    return "{\"requestId\":\""
        + responseId
        + "\",\"stillRequiredFragmentIds\":[],\"releasableFragmentIds\":[],"
        + "\"reverseProbeRequested\":false,\"respondedAt\":\"2026-04-25T10:00:01Z\"}";
  }
}
