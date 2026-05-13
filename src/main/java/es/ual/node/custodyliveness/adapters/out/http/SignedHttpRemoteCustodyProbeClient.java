package es.ual.node.custodyliveness.adapters.out.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.ual.node.bootstrap.configuration.NodeTopologyProperties;
import es.ual.node.bootstrap.observability.RequestCorrelationInterceptor;
import es.ual.node.bootstrap.observability.TraceContextHttpInjector;
import es.ual.node.custodyliveness.application.CustodyLivenessProperties;
import es.ual.node.custodyliveness.domain.CustodyProbeFragment;
import es.ual.node.custodyliveness.domain.CustodyProbeRequest;
import es.ual.node.custodyliveness.domain.CustodyProbeResponse;
import es.ual.node.custodyliveness.ports.out.RemoteCustodyProbeClientPort;
import es.ual.node.identitysecurity.adapters.in.web.RequestSignatureValidator;
import es.ual.node.identitysecurity.application.NodeIdentityContext;
import es.ual.node.identitysecurity.application.RequestSignatureValidationService;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.OpenTelemetry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;

/** Signed HTTP implementation for remote custody liveness probes. */
public class SignedHttpRemoteCustodyProbeClient implements RemoteCustodyProbeClientPort {

  private static final String PROBE_PATH = "/custody/liveness/probes";

  private final NodeIdentityContext nodeIdentityContext;
  private final NodeTopologyProperties topologyProperties;
  private final CustodyLivenessProperties livenessProperties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final String signatureAlgorithm;
  private final Tracer tracer;
  private final TraceContextHttpInjector traceInjector;

  /**
   * Convenience constructor para tests que no instrumentan tracing. Usa {@link Tracer#NOOP} + un
   * injector con OpenTelemetry no-op (no añade headers traceparent). Los tests funcionales
   * (signatures, request-id) no necesitan modificarse.
   */
  public SignedHttpRemoteCustodyProbeClient(
      final NodeIdentityContext nodeIdentityContext,
      final NodeTopologyProperties topologyProperties,
      final CustodyLivenessProperties livenessProperties,
      final ObjectMapper objectMapper,
      final String signatureAlgorithm) {
    this(
        nodeIdentityContext,
        topologyProperties,
        livenessProperties,
        objectMapper,
        signatureAlgorithm,
        Tracer.NOOP,
        new TraceContextHttpInjector(OpenTelemetry.noop()));
  }

  /** Creates signed HTTP probe client with full tracing wiring (production constructor). */
  public SignedHttpRemoteCustodyProbeClient(
      final NodeIdentityContext nodeIdentityContext,
      final NodeTopologyProperties topologyProperties,
      final CustodyLivenessProperties livenessProperties,
      final ObjectMapper objectMapper,
      final String signatureAlgorithm,
      final Tracer tracer,
      final TraceContextHttpInjector traceInjector) {
    if (nodeIdentityContext == null
        || topologyProperties == null
        || livenessProperties == null
        || objectMapper == null
        || tracer == null
        || traceInjector == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    if (signatureAlgorithm == null || signatureAlgorithm.isBlank()) {
      throw new IllegalArgumentException("signatureAlgorithm must not be blank");
    }
    this.nodeIdentityContext = nodeIdentityContext;
    this.topologyProperties = topologyProperties;
    this.livenessProperties = livenessProperties;
    this.objectMapper = objectMapper;
    this.signatureAlgorithm = signatureAlgorithm.trim();
    this.tracer = tracer;
    this.traceInjector = traceInjector;
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(
                Duration.ofMillis(Math.max(250L, livenessProperties.getRequestTimeoutMillis())))
            .build();
  }

  @Override
  public CustodyProbeResponse probe(final String remoteNodeId, final CustodyProbeRequest request) {
    if (remoteNodeId == null || remoteNodeId.isBlank()) {
      throw new IllegalArgumentException("remoteNodeId must not be blank");
    }
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }

    final String baseUrl = resolveBaseUrl(remoteNodeId.trim());
    final URI uri = URI.create(normalizeBaseUrl(baseUrl) + PROBE_PATH);
    final String nonce = UUID.randomUUID().toString();
    final long timestamp = Instant.now().getEpochSecond();
    final String canonicalPayload =
        RequestSignatureValidationService.buildCanonicalPayload(
            "POST", PROBE_PATH, null, nonce, timestamp);
    final String signature =
        nodeIdentityContext.signBase64(
            signatureAlgorithm, canonicalPayload.getBytes(StandardCharsets.UTF_8));

    final ProbeRequestBody requestBody = ProbeRequestBody.fromDomain(request);
    final String outboundRequestId = resolveOutboundRequestId(requestBody.requestId);
    final byte[] payloadBytes;
    try {
      payloadBytes = objectMapper.writeValueAsBytes(requestBody);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to serialize custody probe request", exception);
    }

    final HttpRequest.Builder requestBuilder =
        HttpRequest.newBuilder(uri)
            .timeout(
                Duration.ofMillis(Math.max(250L, livenessProperties.getRequestTimeoutMillis())))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header(RequestCorrelationInterceptor.HEADER_REQUEST_ID, outboundRequestId)
            .header(RequestSignatureValidator.HEADER_NODE_ID, nodeIdentityContext.nodeId())
            .header(RequestSignatureValidator.HEADER_NONCE, nonce)
            .header(RequestSignatureValidator.HEADER_TIMESTAMP, String.valueOf(timestamp))
            .header(RequestSignatureValidator.HEADER_SIGNATURE_ALGORITHM, signatureAlgorithm)
            .header(RequestSignatureValidator.HEADER_SIGNATURE, signature)
            .POST(HttpRequest.BodyPublishers.ofByteArray(payloadBytes));

    // Span outbound + W3C trace context propagation. La firma ya esta calculada
    // sobre canonical (method, path, body, nonce, timestamp), inyectar traceparent NO
    // afecta a la signature.
    final Span span =
        tracer
            .nextSpan()
            .name("node.custody.probe.outbound")
            .tag("peer.node.id", remoteNodeId)
            .tag("http.url", uri.toString())
            .tag("http.method", "POST")
            .start();

    try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
      traceInjector.inject(requestBuilder);
      final HttpRequest httpRequest = requestBuilder.build();
      final HttpResponse<String> response;
      try {
        response =
            httpClient.send(
                httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      } catch (IOException | InterruptedException exception) {
        if (exception instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        span.error(exception);
        throw new IllegalStateException("Remote custody probe request failed", exception);
      }
      span.tag("http.status_code", String.valueOf(response.statusCode()));

      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        final IllegalStateException ex =
            new IllegalStateException(
                "Remote custody probe rejected with HTTP status " + response.statusCode());
        span.error(ex);
        throw ex;
      }

      final ProbeResponseBody responseBody;
      try {
        responseBody = objectMapper.readValue(response.body(), ProbeResponseBody.class);
      } catch (JsonProcessingException exception) {
        span.error(exception);
        throw new IllegalStateException("Unable to parse remote custody probe response", exception);
      }
      return responseBody.toDomain();
    } finally {
      span.end();
    }
  }

  private String resolveBaseUrl(final String remoteNodeId) {
    if (remoteNodeId.startsWith("http://") || remoteNodeId.startsWith("https://")) {
      return remoteNodeId;
    }

    final String tutorNodeId = topologyProperties.getTutorNodeId();
    if (tutorNodeId != null
        && !tutorNodeId.isBlank()
        && tutorNodeId.trim().equals(remoteNodeId)
        && topologyProperties.getTutorBaseUrl() != null
        && !topologyProperties.getTutorBaseUrl().isBlank()) {
      return topologyProperties.getTutorBaseUrl().trim();
    }

    final Map<String, String> configured = livenessProperties.getRemoteBaseUrls();
    final String resolved = configured.get(remoteNodeId);
    if (resolved != null && !resolved.isBlank()) {
      return resolved.trim();
    }

    throw new IllegalStateException(
        "No remote base URL configured for nodeId "
            + remoteNodeId
            + ". Configure node.custody-liveness.remote-base-urls.");
  }

  private String normalizeBaseUrl(final String baseUrl) {
    final String trimmed = baseUrl == null ? "" : baseUrl.trim();
    if (trimmed.isBlank()) {
      throw new IllegalArgumentException("baseUrl must not be blank");
    }
    if (trimmed.endsWith("/")) {
      return trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed;
  }

  private String resolveOutboundRequestId(final String domainRequestId) {
    if (domainRequestId != null && !domainRequestId.isBlank()) {
      return domainRequestId.trim();
    }
    final String contextual = MDC.get(RequestCorrelationInterceptor.MDC_REQUEST_ID_KEY);
    if (contextual != null && !contextual.isBlank()) {
      return contextual;
    }
    return UUID.randomUUID().toString();
  }

  /** Request DTO for remote JSON payload. */
  public static final class ProbeRequestBody {

    private String requestId;
    private String requesterNodeId;
    private String targetNodeId;
    private List<ProbeFragmentBody> fragments;
    private Instant requestedAt;
    private long reverseProbeWindowSeconds;

    public static ProbeRequestBody fromDomain(final CustodyProbeRequest request) {
      final ProbeRequestBody body = new ProbeRequestBody();
      body.requestId = request.requestId();
      body.requesterNodeId = request.requesterNodeId();
      body.targetNodeId = request.targetNodeId();
      body.fragments =
          request.fragments() == null
              ? List.of()
              : request.fragments().stream().map(ProbeFragmentBody::fromDomain).toList();
      body.requestedAt = request.requestedAt();
      body.reverseProbeWindowSeconds = request.reverseProbeWindowSeconds();
      return body;
    }

    public String getRequestId() {
      return requestId;
    }

    public String getRequesterNodeId() {
      return requesterNodeId;
    }

    public String getTargetNodeId() {
      return targetNodeId;
    }

    public List<ProbeFragmentBody> getFragments() {
      return fragments;
    }

    public Instant getRequestedAt() {
      return requestedAt;
    }

    public long getReverseProbeWindowSeconds() {
      return reverseProbeWindowSeconds;
    }
  }

  /** Fragment DTO for probe payload. */
  public static final class ProbeFragmentBody {

    private String fragmentId;
    private String agreementId;
    private String checksum;
    private long sizeBytes;

    public static ProbeFragmentBody fromDomain(final CustodyProbeFragment fragment) {
      final ProbeFragmentBody body = new ProbeFragmentBody();
      body.fragmentId = fragment.fragmentId();
      body.agreementId = fragment.agreementId();
      body.checksum = fragment.checksum();
      body.sizeBytes = fragment.sizeBytes();
      return body;
    }

    public String getFragmentId() {
      return fragmentId;
    }

    public String getAgreementId() {
      return agreementId;
    }

    public String getChecksum() {
      return checksum;
    }

    public long getSizeBytes() {
      return sizeBytes;
    }
  }

  /** Response DTO from remote probe endpoint. */
  public static final class ProbeResponseBody {

    private String requestId;
    private List<String> stillRequiredFragmentIds;
    private List<String> releasableFragmentIds;
    private boolean reverseProbeRequested;
    private Instant reverseProbeNotBefore;
    private Instant respondedAt;

    public CustodyProbeResponse toDomain() {
      return new CustodyProbeResponse(
          requestId,
          stillRequiredFragmentIds == null ? List.of() : List.copyOf(stillRequiredFragmentIds),
          releasableFragmentIds == null ? List.of() : List.copyOf(releasableFragmentIds),
          reverseProbeRequested,
          reverseProbeNotBefore,
          respondedAt);
    }

    public String getRequestId() {
      return requestId;
    }

    public void setRequestId(final String requestId) {
      this.requestId = requestId;
    }

    public List<String> getStillRequiredFragmentIds() {
      return stillRequiredFragmentIds;
    }

    public void setStillRequiredFragmentIds(final List<String> stillRequiredFragmentIds) {
      this.stillRequiredFragmentIds = stillRequiredFragmentIds;
    }

    public List<String> getReleasableFragmentIds() {
      return releasableFragmentIds;
    }

    public void setReleasableFragmentIds(final List<String> releasableFragmentIds) {
      this.releasableFragmentIds = releasableFragmentIds;
    }

    public boolean isReverseProbeRequested() {
      return reverseProbeRequested;
    }

    public void setReverseProbeRequested(final boolean reverseProbeRequested) {
      this.reverseProbeRequested = reverseProbeRequested;
    }

    public Instant getReverseProbeNotBefore() {
      return reverseProbeNotBefore;
    }

    public void setReverseProbeNotBefore(final Instant reverseProbeNotBefore) {
      this.reverseProbeNotBefore = reverseProbeNotBefore;
    }

    public Instant getRespondedAt() {
      return respondedAt;
    }

    public void setRespondedAt(final Instant respondedAt) {
      this.respondedAt = respondedAt;
    }
  }
}
