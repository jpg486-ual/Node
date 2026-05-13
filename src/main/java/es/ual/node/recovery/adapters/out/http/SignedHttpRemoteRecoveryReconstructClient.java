package es.ual.node.recovery.adapters.out.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ual.node.identitysecurity.adapters.in.web.RequestSignatureValidator;
import es.ual.node.identitysecurity.application.NodeIdentityContext;
import es.ual.node.identitysecurity.application.RequestSignatureValidationService;
import es.ual.node.recovery.ports.out.RemoteRecoveryReconstructClientPort;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Signed HTTP client para {@code POST /recovery/fragments/reconstruct}. Usado por {@code
 * NodeFsRestoreService} en mode RESTORE + strategy BYTES_FROM_TUTOR para pullear bytes
 * reconstruidos del tutor cuando los peers ya no tienen los fragments tras RETURN_TO_TUTOR.
 */
public class SignedHttpRemoteRecoveryReconstructClient
    implements RemoteRecoveryReconstructClientPort {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(SignedHttpRemoteRecoveryReconstructClient.class);
  private static final String PATH = "/recovery/fragments/reconstruct";
  private static final Duration TIMEOUT = Duration.ofSeconds(60);

  private final NodeIdentityContext nodeIdentityContext;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final String signatureAlgorithm;

  /** Creates client. */
  public SignedHttpRemoteRecoveryReconstructClient(
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
  public byte[] reconstruct(
      final String tutorBaseUrl,
      final String fileId,
      final String expectedOriginalHash,
      final int redundancyN,
      final int redundancyK,
      final int symbolSize,
      final List<FragmentReference> fragments) {
    if (tutorBaseUrl == null || tutorBaseUrl.isBlank()) {
      throw new IllegalArgumentException("tutorBaseUrl must not be blank");
    }
    if (fragments == null || fragments.isEmpty()) {
      throw new IllegalArgumentException("fragments must not be empty");
    }

    final URI uri = URI.create(normalizeBaseUrl(tutorBaseUrl) + PATH);
    final String nonce = "recovery-reconstruct-" + UUID.randomUUID();
    final long timestamp = Instant.now().getEpochSecond();
    final String canonical =
        RequestSignatureValidationService.buildCanonicalPayload(
            "POST", PATH, null, nonce, timestamp);
    final String signature =
        nodeIdentityContext.signBase64(
            signatureAlgorithm, canonical.getBytes(StandardCharsets.UTF_8));

    final List<Map<String, Object>> fragmentRefs =
        fragments.stream()
            .map(
                f -> {
                  final java.util.LinkedHashMap<String, Object> entry =
                      new java.util.LinkedHashMap<>();
                  entry.put("fragmentId", f.fragmentId());
                  entry.put("index", f.index());
                  entry.put("parity", f.parity());
                  return (Map<String, Object>) entry;
                })
            .toList();
    final java.util.LinkedHashMap<String, Object> bodyMap = new java.util.LinkedHashMap<>();
    bodyMap.put("fileId", fileId);
    bodyMap.put("expectedOriginalHash", expectedOriginalHash);
    bodyMap.put("redundancyN", redundancyN);
    bodyMap.put("redundancyK", redundancyK);
    bodyMap.put("symbolSize", symbolSize);
    bodyMap.put("fragments", fragmentRefs);

    final String bodyJson;
    try {
      bodyJson = objectMapper.writeValueAsString(bodyMap);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to serialize reconstruct request", ex);
    }

    final HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/octet-stream")
            .header(RequestSignatureValidator.HEADER_NODE_ID, nodeIdentityContext.nodeId())
            .header(RequestSignatureValidator.HEADER_NONCE, nonce)
            .header(RequestSignatureValidator.HEADER_TIMESTAMP, String.valueOf(timestamp))
            .header(RequestSignatureValidator.HEADER_SIGNATURE_ALGORITHM, signatureAlgorithm)
            .header(RequestSignatureValidator.HEADER_SIGNATURE, signature)
            .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
            .build();

    try {
      final HttpResponse<byte[]> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "Reconstruct from tutor failed with HTTP " + response.statusCode() + " path=" + uri);
      }
      final byte[] bytes = response.body();
      // Verify checksum header against received bytes (defense-in-depth — tutor already verified
      // RS integrity but a network/middlebox glitch could corrupt mid-flight).
      final String headerChecksum = response.headers().firstValue("X-Checksum").orElse(null);
      if (headerChecksum != null && !headerChecksum.isBlank()) {
        final String computed = sha256Hex(bytes);
        if (!computed.equalsIgnoreCase(headerChecksum.trim())) {
          throw new IllegalStateException(
              "Reconstruct response checksum mismatch: expected "
                  + headerChecksum
                  + " got "
                  + computed);
        }
      }
      LOGGER
          .atInfo()
          .setMessage("Bytes pulled from tutor via reconstruct")
          .addKeyValue("event", "BYTES_FROM_TUTOR_PULL")
          .addKeyValue("tutorBaseUrl", tutorBaseUrl)
          .addKeyValue("fileId", fileId)
          .addKeyValue("sizeBytes", bytes.length)
          .addKeyValue("fragmentCount", fragments.size())
          .log();
      return bytes;
    } catch (java.io.IOException ex) {
      throw new IllegalStateException("Reconstruct I/O error to " + uri, ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Reconstruct interrupted to " + uri, ex);
    }
  }

  private static String sha256Hex(final byte[] bytes) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(bytes));
    } catch (Exception ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }

  private static String normalizeBaseUrl(final String baseUrl) {
    final String trimmed = baseUrl.trim();
    return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
  }
}
