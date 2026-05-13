package es.ual.node.custodyliveness.adapters.out.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.ual.node.bootstrap.observability.RequestCorrelationInterceptor;
import es.ual.node.bootstrap.observability.TraceContextHttpInjector;
import es.ual.node.custodyliveness.application.CustodyLivenessProperties;
import es.ual.node.custodyliveness.ports.out.RemoteOriginKeepListClientPort;
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
import java.util.UUID;

/**
 * Adapter HTTP firmado del custodian al origen para el probe periódico de keep-list. Endpoint:
 * {@code POST /ops/custody-liveness/keep-list-request}.
 */
public class SignedHttpRemoteOriginKeepListClient implements RemoteOriginKeepListClientPort {

  private static final String PATH = "/ops/custody-liveness/keep-list-request";

  private final NodeIdentityContext nodeIdentityContext;
  private final CustodyLivenessProperties livenessProperties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final String signatureAlgorithm;
  private final Tracer tracer;
  private final TraceContextHttpInjector traceInjector;

  /** Convenience constructor sin tracing (tests). */
  public SignedHttpRemoteOriginKeepListClient(
      final NodeIdentityContext nodeIdentityContext,
      final CustodyLivenessProperties livenessProperties,
      final ObjectMapper objectMapper,
      final String signatureAlgorithm) {
    this(
        nodeIdentityContext,
        livenessProperties,
        objectMapper,
        signatureAlgorithm,
        Tracer.NOOP,
        new TraceContextHttpInjector(OpenTelemetry.noop()));
  }

  /** Production constructor con tracing. */
  public SignedHttpRemoteOriginKeepListClient(
      final NodeIdentityContext nodeIdentityContext,
      final CustodyLivenessProperties livenessProperties,
      final ObjectMapper objectMapper,
      final String signatureAlgorithm,
      final Tracer tracer,
      final TraceContextHttpInjector traceInjector) {
    if (nodeIdentityContext == null
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
  public List<String> requestKeepList(
      final String originBaseUrl, final String requesterNodeId, final List<String> fragmentIds) {
    if (originBaseUrl == null || originBaseUrl.isBlank()) {
      throw new IllegalArgumentException("originBaseUrl must not be blank");
    }
    if (requesterNodeId == null || requesterNodeId.isBlank()) {
      throw new IllegalArgumentException("requesterNodeId must not be blank");
    }
    final List<String> safeFragmentIds = fragmentIds == null ? List.of() : List.copyOf(fragmentIds);
    final URI uri = URI.create(normalizeBaseUrl(originBaseUrl) + PATH);
    final String nonce = UUID.randomUUID().toString();
    final long timestamp = Instant.now().getEpochSecond();
    final String canonicalPayload =
        RequestSignatureValidationService.buildCanonicalPayload(
            "POST", PATH, null, nonce, timestamp);
    final String signature =
        nodeIdentityContext.signBase64(
            signatureAlgorithm, canonicalPayload.getBytes(StandardCharsets.UTF_8));

    final byte[] body;
    try {
      body =
          objectMapper.writeValueAsBytes(new RequestBody(requesterNodeId.trim(), safeFragmentIds));
    } catch (JsonProcessingException exception) {
      throw new RemoteOriginKeepListException(
          "Unable to serialize keep-list-request body", exception);
    }

    final HttpRequest.Builder requestBuilder =
        HttpRequest.newBuilder(uri)
            .timeout(
                Duration.ofMillis(Math.max(250L, livenessProperties.getRequestTimeoutMillis())))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header(RequestCorrelationInterceptor.HEADER_REQUEST_ID, UUID.randomUUID().toString())
            .header(RequestSignatureValidator.HEADER_NODE_ID, nodeIdentityContext.nodeId())
            .header(RequestSignatureValidator.HEADER_NONCE, nonce)
            .header(RequestSignatureValidator.HEADER_TIMESTAMP, String.valueOf(timestamp))
            .header(RequestSignatureValidator.HEADER_SIGNATURE_ALGORITHM, signatureAlgorithm)
            .header(RequestSignatureValidator.HEADER_SIGNATURE, signature)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body));

    final Span span =
        tracer
            .nextSpan()
            .name("node.custody.keeplist.outbound")
            .tag("peer.node.id", requesterNodeId)
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
        throw new RemoteOriginKeepListException("Keep-list-request failed (network)", exception);
      }
      span.tag("http.status_code", String.valueOf(response.statusCode()));
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        final RemoteOriginKeepListException ex =
            new RemoteOriginKeepListException(
                "Keep-list-request rejected HTTP " + response.statusCode());
        span.error(ex);
        throw ex;
      }
      final ResponseBody parsed;
      try {
        parsed = objectMapper.readValue(response.body(), ResponseBody.class);
      } catch (JsonProcessingException exception) {
        span.error(exception);
        throw new RemoteOriginKeepListException(
            "Unable to parse keep-list-response body", exception);
      }
      return parsed.keepFragmentIds == null ? List.of() : List.copyOf(parsed.keepFragmentIds);
    } finally {
      span.end();
    }
  }

  private String normalizeBaseUrl(final String baseUrl) {
    final String trimmed = baseUrl.trim();
    return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
  }

  /** Wire body. */
  private record RequestBody(String requesterNodeId, List<String> fragmentIds) {}

  /** Wire response body. */
  private static final class ResponseBody {
    public List<String> keepFragmentIds;
  }
}
