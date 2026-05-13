package es.ual.node.recovery.adapters.out.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ual.node.identitysecurity.adapters.in.web.RequestSignatureValidator;
import es.ual.node.identitysecurity.application.NodeIdentityContext;
import es.ual.node.identitysecurity.application.RequestSignatureValidationService;
import es.ual.node.recovery.adapters.in.web.TutorManifestInventoryResponsePayload;
import es.ual.node.recovery.ports.out.RemoteTutorManifestInventoryClientPort;
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

/** Signed HTTP client (origen-side) for {@code GET /recovery/file-manifests/inventory}. */
public class SignedHttpRemoteTutorManifestInventoryClient
    implements RemoteTutorManifestInventoryClientPort {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(SignedHttpRemoteTutorManifestInventoryClient.class);
  private static final String PATH = "/recovery/file-manifests/inventory";
  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final NodeIdentityContext nodeIdentityContext;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final String signatureAlgorithm;

  /** Creates client. */
  public SignedHttpRemoteTutorManifestInventoryClient(
      final NodeIdentityContext nodeIdentityContext,
      final ObjectMapper objectMapper,
      final String signatureAlgorithm) {
    if (nodeIdentityContext == null
        || objectMapper == null
        || signatureAlgorithm == null
        || signatureAlgorithm.isBlank()) {
      throw new IllegalArgumentException("dependencies must not be null/blank");
    }
    this.nodeIdentityContext = nodeIdentityContext;
    this.objectMapper = objectMapper;
    this.signatureAlgorithm = signatureAlgorithm.trim();
    this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  }

  @Override
  public List<String> fetchInventory(final String tutorBaseUrl) {
    if (tutorBaseUrl == null || tutorBaseUrl.isBlank()) {
      throw new IllegalArgumentException("tutorBaseUrl must not be blank");
    }
    final URI uri = URI.create(normalizeBaseUrl(tutorBaseUrl) + PATH);
    final String nonce = "manifest-inventory-" + UUID.randomUUID();
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
            "Manifest inventory failed HTTP " + response.statusCode() + " body=" + response.body());
      }
      final TutorManifestInventoryResponsePayload payload =
          objectMapper.readValue(response.body(), TutorManifestInventoryResponsePayload.class);
      if (payload == null || payload.fileIds() == null) {
        return List.of();
      }
      return List.copyOf(payload.fileIds());
    } catch (IOException ex) {
      throw new IllegalStateException("Manifest inventory I/O error from " + uri, ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Manifest inventory interrupted from " + uri, ex);
    } finally {
      LOGGER.atDebug().setMessage("Manifest inventory completed").addKeyValue("uri", uri).log();
    }
  }

  private static String normalizeBaseUrl(final String baseUrl) {
    final String trimmed = baseUrl.trim();
    return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
  }
}
