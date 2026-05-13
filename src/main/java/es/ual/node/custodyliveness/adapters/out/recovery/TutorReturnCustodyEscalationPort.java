package es.ual.node.custodyliveness.adapters.out.recovery;

import es.ual.node.bootstrap.configuration.NodeTopologyProperties;
import es.ual.node.bootstrap.observability.RequestCorrelationInterceptor;
import es.ual.node.bootstrap.observability.TraceContextHttpInjector;
import es.ual.node.custodyliveness.application.CustodyLivenessObservabilityService;
import es.ual.node.custodyliveness.application.CustodyLivenessProperties;
import es.ual.node.custodyliveness.domain.CustodyEscalationPolicy;
import es.ual.node.custodyliveness.domain.CustodyProbeFragment;
import es.ual.node.custodyliveness.domain.CustodyProbeSession;
import es.ual.node.custodyliveness.ports.out.CustodyEscalationPort;
import es.ual.node.custodyliveness.ports.out.CustodyFragmentLifecyclePort;
import es.ual.node.fragmentstorage.domain.CustodyFragment;
import es.ual.node.fragmentstorage.ports.out.CustodyFragmentPayloadPort;
import es.ual.node.fragmentstorage.ports.out.CustodyFragmentPort;
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
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Escalation adapter that returns custodied fragments to tutor when remote node is unresponsive.
 */
public class TutorReturnCustodyEscalationPort implements CustodyEscalationPort {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(TutorReturnCustodyEscalationPort.class);
  private static final String RECOVERY_STORE_PATH = "/recovery/fragments";

  private final NodeTopologyProperties nodeTopologyProperties;
  private final NodeIdentityContext nodeIdentityContext;
  private final CustodyFragmentPort custodyFragmentPort;
  private final CustodyFragmentPayloadPort custodyFragmentPayloadPort;
  private final CustodyLivenessProperties custodyLivenessProperties;
  // Cuando el POST al tutor falla (timeout / 4xx / 5xx), renovamos el TTL del
  // custody_fragment para que sobreviva al outage del tutor. El próximo ciclo de probes
  // (cada base-interval-seconds) re-disparará handleUnresponsive y volverá a intentar.
  // Cero borrado peer-side ocurre en la rama de fallo.
  private final CustodyFragmentLifecyclePort fragmentLifecyclePort;
  private final CustodyLivenessObservabilityService observabilityService;
  private final HttpClient httpClient;
  private final String signatureAlgorithm;
  private final Tracer tracer;
  private final TraceContextHttpInjector traceInjector;

  /** Convenience constructor for tests not exercising tracing. */
  public TutorReturnCustodyEscalationPort(
      final NodeTopologyProperties nodeTopologyProperties,
      final NodeIdentityContext nodeIdentityContext,
      final CustodyFragmentPort custodyFragmentPort,
      final CustodyFragmentPayloadPort custodyFragmentPayloadPort,
      final CustodyLivenessProperties custodyLivenessProperties,
      final CustodyFragmentLifecyclePort fragmentLifecyclePort,
      final CustodyLivenessObservabilityService observabilityService,
      final String signatureAlgorithm) {
    this(
        nodeTopologyProperties,
        nodeIdentityContext,
        custodyFragmentPort,
        custodyFragmentPayloadPort,
        custodyLivenessProperties,
        fragmentLifecyclePort,
        observabilityService,
        signatureAlgorithm,
        Tracer.NOOP,
        new TraceContextHttpInjector(OpenTelemetry.noop()));
  }

  /** Creates adapter with full tracing wiring (production constructor). */
  public TutorReturnCustodyEscalationPort(
      final NodeTopologyProperties nodeTopologyProperties,
      final NodeIdentityContext nodeIdentityContext,
      final CustodyFragmentPort custodyFragmentPort,
      final CustodyFragmentPayloadPort custodyFragmentPayloadPort,
      final CustodyLivenessProperties custodyLivenessProperties,
      final CustodyFragmentLifecyclePort fragmentLifecyclePort,
      final CustodyLivenessObservabilityService observabilityService,
      final String signatureAlgorithm,
      final Tracer tracer,
      final TraceContextHttpInjector traceInjector) {
    if (nodeTopologyProperties == null
        || nodeIdentityContext == null
        || custodyFragmentPort == null
        || custodyFragmentPayloadPort == null
        || custodyLivenessProperties == null
        || fragmentLifecyclePort == null
        || observabilityService == null
        || tracer == null
        || traceInjector == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    if (signatureAlgorithm == null || signatureAlgorithm.isBlank()) {
      throw new IllegalArgumentException("signatureAlgorithm must not be blank");
    }
    this.nodeTopologyProperties = nodeTopologyProperties;
    this.nodeIdentityContext = nodeIdentityContext;
    this.custodyFragmentPort = custodyFragmentPort;
    this.custodyFragmentPayloadPort = custodyFragmentPayloadPort;
    this.custodyLivenessProperties = custodyLivenessProperties;
    this.fragmentLifecyclePort = fragmentLifecyclePort;
    this.observabilityService = observabilityService;
    this.signatureAlgorithm = signatureAlgorithm.trim();
    this.tracer = tracer;
    this.traceInjector = traceInjector;
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(
                Duration.ofMillis(
                    Math.max(250L, custodyLivenessProperties.getRequestTimeoutMillis())))
            .build();
  }

  @Override
  public void handleUnresponsive(
      final CustodyProbeSession session,
      final List<CustodyProbeFragment> fragments,
      final String reason,
      final Instant detectedAt,
      final CustodyEscalationPolicy policy) {
    if (session == null || detectedAt == null || policy == null) {
      throw new IllegalArgumentException("session, detectedAt and policy must not be null");
    }

    if (policy != CustodyEscalationPolicy.RETURN_TO_TUTOR) {
      LOGGER
          .atWarn()
          .setMessage("Custody liveness escalation applied with KEEP_AND_ALERT policy")
          .addKeyValue("sessionId", session.sessionId())
          .addKeyValue("remoteNodeId", session.remoteNodeId())
          .addKeyValue("fragmentCount", fragments == null ? 0 : fragments.size())
          .addKeyValue("detectedAt", detectedAt)
          .addKeyValue("reason", reason)
          .log();
      return;
    }

    final String tutorBaseUrl = resolveTutorBaseUrl(session);

    // If THIS node is
    // both tutor of the requester AND custodian of its custody fragments, the HTTP roundtrip to
    // /recovery/fragments lands the orphan in `recovery_orphan_fragment` (recovery domain) while
    // we still delete the original from `custody_fragment` (custody domain). Two physically
    // separate tables: no duplication, no overwrite, no loop possible.

    final List<CustodyProbeFragment> inventory =
        fragments == null ? List.of() : List.copyOf(fragments);
    for (CustodyProbeFragment fragment : inventory) {
      if (fragment == null || fragment.fragmentId() == null || fragment.fragmentId().isBlank()) {
        continue;
      }
      returnFragmentToTutor(tutorBaseUrl, session.remoteNodeId(), fragment);
    }

    LOGGER
        .atWarn()
        .setMessage("Custody liveness escalation completed with RETURN_TO_TUTOR policy")
        .addKeyValue("sessionId", session.sessionId())
        .addKeyValue("remoteNodeId", session.remoteNodeId())
        .addKeyValue("fragmentCount", inventory.size())
        .addKeyValue("detectedAt", detectedAt)
        .addKeyValue("reason", reason)
        .log();
  }

  private void returnFragmentToTutor(
      final String tutorBaseUrl,
      final String requesterNodeId,
      final CustodyProbeFragment fragment) {
    final String fragmentId = fragment.fragmentId().trim();
    final CustodyFragment stored =
        custodyFragmentPort
            .findByFragmentId(fragmentId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Recovery metadata not found for fragment " + fragmentId));

    final byte[] payload =
        custodyFragmentPayloadPort
            .findByFragmentId(fragmentId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Recovery payload not found for fragment " + fragmentId));

    final String requestPath = RECOVERY_STORE_PATH;
    final String nonce = UUID.randomUUID().toString();
    final long timestamp = Instant.now().getEpochSecond();
    final String canonicalPayload =
        RequestSignatureValidationService.buildCanonicalPayload(
            "POST", requestPath, null, nonce, timestamp);
    final String signature =
        nodeIdentityContext.signBase64(
            signatureAlgorithm, canonicalPayload.getBytes(StandardCharsets.UTF_8));

    final String requesterPublicKey =
        Base64.getEncoder().encodeToString(nodeIdentityContext.publicKey().getEncoded());
    final String outboundRequestId = resolveOutboundRequestId();
    final URI uri = URI.create(normalizeBaseUrl(tutorBaseUrl) + requestPath);
    final HttpRequest.Builder requestBuilder =
        HttpRequest.newBuilder(uri)
            .timeout(
                Duration.ofMillis(
                    Math.max(250L, custodyLivenessProperties.getRequestTimeoutMillis())))
            .header("Content-Type", "application/octet-stream")
            .header("Accept", "application/json")
            .header(RequestCorrelationInterceptor.HEADER_REQUEST_ID, outboundRequestId)
            .header("X-Fragment-Id", fragmentId)
            .header("X-Agreement-Id", fragment.agreementId())
            .header("X-Requester-Node-Id", requesterNodeId)
            .header("X-Requester-Public-Key", requesterPublicKey)
            .header("X-Checksum-Algorithm", stored.checksumAlgorithm())
            .header("X-Checksum", fragment.checksum())
            .header(RequestSignatureValidator.HEADER_NODE_ID, nodeIdentityContext.nodeId())
            .header(RequestSignatureValidator.HEADER_NONCE, nonce)
            .header(RequestSignatureValidator.HEADER_TIMESTAMP, String.valueOf(timestamp))
            .header(RequestSignatureValidator.HEADER_SIGNATURE_ALGORITHM, signatureAlgorithm)
            .header(RequestSignatureValidator.HEADER_SIGNATURE, signature)
            .POST(HttpRequest.BodyPublishers.ofByteArray(payload));

    // Span outbound + W3C trace context propagation. Firma sobre canonical
    // (method, path, body, nonce, timestamp); inyectar traceparent NO afecta la firma.
    final Span span =
        tracer
            .nextSpan()
            .name("node.custody.tutor.return")
            .tag("peer.node.id", requesterNodeId)
            .tag("fragment.id", fragmentId)
            .tag("http.url", uri.toString())
            .tag("http.method", "POST")
            .start();

    try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
      traceInjector.inject(requestBuilder);
      final HttpRequest request = requestBuilder.build();
      final HttpResponse<String> response;
      try {
        response =
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      } catch (IOException | InterruptedException exception) {
        if (exception instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        span.error(exception);
        deferEscalationAndRenewTtl(fragmentId, "tutor_unreachable", exception.getMessage());
        throw new IllegalStateException(
            "Failed to return fragment " + fragmentId + " to tutor; TTL renewed", exception);
      }

      final int status = response.statusCode();
      span.tag("http.status_code", String.valueOf(status));

      if (status != 201 && status != 409) {
        final IllegalStateException ex =
            new IllegalStateException(
                "Tutor rejected returned fragment " + fragmentId + " with HTTP status " + status);
        span.error(ex);
        deferEscalationAndRenewTtl(fragmentId, "tutor_rejected_" + status, ex.getMessage());
        throw ex;
      }

      custodyFragmentPayloadPort.deleteByFragmentId(fragmentId);
      custodyFragmentPort.deleteByFragmentId(fragmentId);
    } finally {
      span.end();
    }
  }

  /**
   * Renews the custody fragment TTL and emits a high-severity WARN when the POST to tutor fails
   * (timeout / 4xx / 5xx not 201/409). The peer-side {@code custody_fragment} is preserved and the
   * natural probe cycle (every {@code base-interval-seconds}) will re-dispatch escalation. When the
   * tutor recovers, the next escalation cycle will succeed and the fragment will migrate to {@code
   * recovery_orphan_fragment} via the normal flow.
   */
  private void deferEscalationAndRenewTtl(
      final String fragmentId, final String cause, final String detail) {
    try {
      fragmentLifecyclePort.extendCustody(
          fragmentId, custodyLivenessProperties.getEscalationTtlRenewalSeconds());
    } catch (RuntimeException renewalEx) {
      LOGGER
          .atError()
          .setMessage("TTL renewal during escalation deferral also failed")
          .addKeyValue("event", "ESCALATION_TTL_RENEWAL_FAILED")
          .addKeyValue("severity", "high")
          .addKeyValue("fragmentId", fragmentId)
          .addKeyValue("cause", renewalEx.getMessage())
          .log();
    }

    observabilityService.onEscalationDeferred();

    LOGGER
        .atWarn()
        .setMessage("Escalation deferred: tutor unreachable, custody TTL renewed")
        .addKeyValue("event", "ESCALATION_DEFERRED_TUTOR_DOWN")
        .addKeyValue("severity", "high")
        .addKeyValue("fragmentId", fragmentId)
        .addKeyValue(
            "ttlRenewalSeconds", custodyLivenessProperties.getEscalationTtlRenewalSeconds())
        .addKeyValue("cause", cause)
        .addKeyValue("detail", detail)
        .log();
  }

  /**
   * Resolves the tutor base URL for a {@code RETURN_TO_TUTOR} escalation.
   *
   * <p>Prefers the requester (remote peer) tutor carried in the probe session ({@link
   * CustodyProbeSession#remoteTutorBaseUrl}). Falls back to the local node tutor configuration when
   * the session does not carry a remote tutor (legacy session created before V20 or when the
   * requester did not propagate its tutor in the originating {@link
   * es.ual.node.negotiation.domain.NegotiationAgreement}). The fallback emits a WARN log so the
   * operator can detect topologies where the assumption "all nodes share one tutor" silently holds.
   *
   * @param session probe session whose remote node is being escalated
   * @return tutor base URL to receive the orphan fragments
   * @throws IllegalStateException when neither the session nor the local config provide a tutor
   */
  private String resolveTutorBaseUrl(final CustodyProbeSession session) {
    if (session != null
        && session.remoteTutorBaseUrl() != null
        && !session.remoteTutorBaseUrl().isBlank()) {
      return session.remoteTutorBaseUrl().trim();
    }

    final String fallback = nodeTopologyProperties.getTutorBaseUrl();
    if (fallback == null || fallback.isBlank()) {
      throw new IllegalStateException(
          "node.topology.tutorBaseUrl must be configured for RETURN_TO_TUTOR when probe"
              + " session does not carry a remote tutor");
    }

    LOGGER
        .atWarn()
        .setMessage(
            "RETURN_TO_TUTOR using local tutor fallback because probe session does not"
                + " carry remoteTutorBaseUrl (legacy session or requester did not propagate"
                + " requesterTutorBaseUrl)")
        .addKeyValue("sessionId", session == null ? null : session.sessionId())
        .addKeyValue("remoteNodeId", session == null ? null : session.remoteNodeId())
        .addKeyValue("fallbackTutorBaseUrl", fallback)
        .log();

    return fallback.trim();
  }

  private String normalizeBaseUrl(final String baseUrl) {
    if (baseUrl.endsWith("/")) {
      return baseUrl.substring(0, baseUrl.length() - 1);
    }
    return baseUrl;
  }

  private String resolveOutboundRequestId() {
    final String contextual = MDC.get(RequestCorrelationInterceptor.MDC_REQUEST_ID_KEY);
    if (contextual != null && !contextual.isBlank()) {
      return contextual;
    }
    return UUID.randomUUID().toString();
  }
}
