package es.ual.node.recovery.adapters.out.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ual.node.bootstrap.configuration.NodeTopologyProperties;
import es.ual.node.identitysecurity.adapters.in.web.RequestSignatureValidator;
import es.ual.node.identitysecurity.application.NodeIdentityContext;
import es.ual.node.identitysecurity.application.RequestSignatureValidationService;
import es.ual.node.recovery.adapters.in.web.CustodiedFileManifestPayload;
import es.ual.node.recovery.adapters.in.web.RecoveryFileManifestListPayload;
import es.ual.node.recovery.domain.CustodiedFileManifest;
import es.ual.node.recovery.ports.out.RemoteCustodiedManifestListClientPort;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Signed HTTP client for {@code GET /recovery/file-manifests}. Used by tutor-keep-list flows to
 * enumerate manifests the tutor holds for this node. Reuses the canonical signature scheme used by
 * the rest of the inter-node clients.
 */
public class SignedHttpRemoteCustodiedManifestListClient
    implements RemoteCustodiedManifestListClientPort {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(SignedHttpRemoteCustodiedManifestListClient.class);
  private static final String PATH = "/recovery/file-manifests";
  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final NodeIdentityContext nodeIdentityContext;
  private final NodeTopologyProperties topologyProperties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final String signatureAlgorithm;

  /** Creates client. */
  public SignedHttpRemoteCustodiedManifestListClient(
      final NodeIdentityContext nodeIdentityContext,
      final NodeTopologyProperties topologyProperties,
      final ObjectMapper objectMapper,
      final String signatureAlgorithm) {
    if (nodeIdentityContext == null
        || topologyProperties == null
        || objectMapper == null
        || signatureAlgorithm == null
        || signatureAlgorithm.isBlank()) {
      throw new IllegalArgumentException("dependencies must not be null/blank");
    }
    this.nodeIdentityContext = nodeIdentityContext;
    this.topologyProperties = topologyProperties;
    this.objectMapper = objectMapper;
    this.signatureAlgorithm = signatureAlgorithm.trim();
    this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  }

  @Override
  public List<CustodiedFileManifest> fetchManifests() {
    final String tutorBaseUrl = topologyProperties.getTutorBaseUrl();
    if (tutorBaseUrl == null || tutorBaseUrl.isBlank()) {
      return List.of();
    }
    final URI uri = URI.create(normalizeBaseUrl(tutorBaseUrl) + PATH);
    final String nonce = "manifest-list-" + UUID.randomUUID();
    final long timestamp = Instant.now().getEpochSecond();
    final String canonicalPayload =
        RequestSignatureValidationService.buildCanonicalPayload(
            "GET", PATH, null, nonce, timestamp);
    final String signature =
        nodeIdentityContext.signBase64(
            signatureAlgorithm, canonicalPayload.getBytes(StandardCharsets.UTF_8));

    final HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(TIMEOUT)
            .header("Accept", "application/json")
            .header(RequestSignatureValidator.HEADER_NODE_ID, nodeIdentityContext.nodeId())
            .header(RequestSignatureValidator.HEADER_NONCE, nonce)
            .header(RequestSignatureValidator.HEADER_TIMESTAMP, String.valueOf(timestamp))
            .header(RequestSignatureValidator.HEADER_SIGNATURE_ALGORITHM, signatureAlgorithm)
            .header(RequestSignatureValidator.HEADER_SIGNATURE, signature)
            .GET()
            .build();

    try {
      final HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "Manifest list failed with HTTP " + response.statusCode() + " body=" + response.body());
      }
      final RecoveryFileManifestListPayload payload =
          objectMapper.readValue(response.body(), RecoveryFileManifestListPayload.class);
      if (payload == null || payload.manifests() == null) {
        return List.of();
      }
      // Payload omits requesterPublicKey on the outbound side (sensitive). Recompose it from the
      // local identity since this client only fetches manifests of *this* node.
      final String selfPublicKey =
          java.util.Base64.getEncoder()
              .encodeToString(nodeIdentityContext.publicKey().getEncoded());
      return payload.manifests().stream().map(p -> toDomain(p, selfPublicKey)).toList();
    } catch (IOException exception) {
      throw new IllegalStateException("Manifest list I/O error from " + uri, exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Manifest list interrupted from " + uri, exception);
    } finally {
      LOGGER.atDebug().setMessage("Manifest list completed").addKeyValue("uri", uri).log();
    }
  }

  private static CustodiedFileManifest toDomain(
      final CustodiedFileManifestPayload payload, final String requesterPublicKey) {
    return new CustodiedFileManifest(
        payload.fileId(),
        payload.requesterNodeId(),
        requesterPublicKey,
        payload.directoryPath(),
        payload.originalFileName(),
        payload.originalFileHash(),
        payload.originalSizeBytes(),
        payload.compressedSizeBytes(),
        payload.compressionAlgorithm(),
        payload.fragmentCount(),
        payload.fragmentSize(),
        payload.redundancyN(),
        payload.redundancyK(),
        payload.fragmentHashes(),
        payload.clientPlacementsJson(),
        null,
        payload.storedAt(),
        payload.lastSupervisedCheckAt(),
        payload.consecutiveOriginFailures());
  }

  private static String normalizeBaseUrl(final String baseUrl) {
    final String trimmed = baseUrl.trim();
    return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
  }
}
