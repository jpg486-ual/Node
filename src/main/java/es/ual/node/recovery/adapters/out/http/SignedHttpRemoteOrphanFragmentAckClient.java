package es.ual.node.recovery.adapters.out.http;

import es.ual.node.identitysecurity.adapters.in.web.RequestSignatureValidator;
import es.ual.node.identitysecurity.application.NodeIdentityContext;
import es.ual.node.identitysecurity.application.RequestSignatureValidationService;
import es.ual.node.recovery.ports.out.RemoteOrphanFragmentAckClientPort;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Signed HTTP client (origin-side) para {@code POST /recovery/orphan-fragments/{fragmentId}/ack}.
 * Invocado por {@link es.ual.node.recovery.application.NodeFsRestoreService} tras un re-upload
 * exitoso para limpiar los orphan fragments del tutor que referencian al {@code oldFileId} ya
 * superado.
 *
 * <p>Idempotente sobre 404 (orphan ya borrado por algún otro path), convertido a no-op silente. Un
 * 4xx/5xx distinto sí propaga {@link IllegalStateException}.
 */
public class SignedHttpRemoteOrphanFragmentAckClient implements RemoteOrphanFragmentAckClientPort {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(SignedHttpRemoteOrphanFragmentAckClient.class);
  private static final String PATH_TEMPLATE = "/recovery/orphan-fragments/%s/ack";
  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final NodeIdentityContext nodeIdentityContext;
  private final HttpClient httpClient;
  private final String signatureAlgorithm;

  /** Creates client. */
  public SignedHttpRemoteOrphanFragmentAckClient(
      final NodeIdentityContext nodeIdentityContext, final String signatureAlgorithm) {
    if (nodeIdentityContext == null || signatureAlgorithm == null || signatureAlgorithm.isBlank()) {
      throw new IllegalArgumentException("dependencies must not be null/blank");
    }
    this.nodeIdentityContext = nodeIdentityContext;
    this.signatureAlgorithm = signatureAlgorithm.trim();
    this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  }

  @Override
  public void ack(final String fragmentId, final String tutorBaseUrl) {
    if (fragmentId == null || fragmentId.isBlank()) {
      throw new IllegalArgumentException("fragmentId must not be blank");
    }
    if (tutorBaseUrl == null || tutorBaseUrl.isBlank()) {
      throw new IllegalArgumentException("tutorBaseUrl must not be blank");
    }
    final String path = String.format(PATH_TEMPLATE, fragmentId.trim());
    final URI uri = URI.create(normalizeBaseUrl(tutorBaseUrl) + path);
    final String nonce = "orphan-ack-" + UUID.randomUUID();
    final long timestamp = Instant.now().getEpochSecond();
    final String canonicalPayload =
        RequestSignatureValidationService.buildCanonicalPayload(
            "POST", path, null, nonce, timestamp);
    final String signature =
        nodeIdentityContext.signBase64(
            signatureAlgorithm, canonicalPayload.getBytes(StandardCharsets.UTF_8));

    final HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(TIMEOUT)
            .header(RequestSignatureValidator.HEADER_NODE_ID, nodeIdentityContext.nodeId())
            .header(RequestSignatureValidator.HEADER_NONCE, nonce)
            .header(RequestSignatureValidator.HEADER_TIMESTAMP, String.valueOf(timestamp))
            .header(RequestSignatureValidator.HEADER_SIGNATURE_ALGORITHM, signatureAlgorithm)
            .header(RequestSignatureValidator.HEADER_SIGNATURE, signature)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

    try {
      final HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      final int status = response.statusCode();
      if (status == 404) {
        LOGGER
            .atDebug()
            .setMessage("Orphan ACK 404 — orphan already absent (idempotent)")
            .addKeyValue("fragmentId", fragmentId)
            .addKeyValue("uri", uri)
            .log();
        return;
      }
      if (status < 200 || status >= 300) {
        throw new IllegalStateException(
            "Orphan ACK failed HTTP " + status + " body=" + response.body());
      }
    } catch (IOException ex) {
      throw new IllegalStateException("Orphan ACK I/O error to " + uri, ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Orphan ACK interrupted to " + uri, ex);
    }
  }

  private static String normalizeBaseUrl(final String baseUrl) {
    final String trimmed = baseUrl.trim();
    return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
  }
}
