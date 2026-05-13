package es.ual.node.discovery.adapters.out.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.ual.node.discovery.domain.DiscoveryCandidateProfile;
import es.ual.node.discovery.ports.out.RemoteDiscoveryCandidateClientPort;
import es.ual.node.identitysecurity.adapters.in.web.RequestSignatureValidator;
import es.ual.node.identitysecurity.application.NodeIdentityContext;
import es.ual.node.identitysecurity.application.RequestSignatureValidationService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Signed HTTP adapter that PUTs the local node's {@link DiscoveryCandidateProfile} on a remote
 * supernode's directory at {@code /ops/discovery/candidates/{nodeId}}. Invoked at startup by the
 * self-registration flow.
 */
public class SignedHttpRemoteDiscoveryCandidateClient
    implements RemoteDiscoveryCandidateClientPort {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(SignedHttpRemoteDiscoveryCandidateClient.class);
  private static final String BASE_PATH = "/ops/discovery/candidates/";
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private final NodeIdentityContext nodeIdentityContext;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final String signatureAlgorithm;

  /** Creates client. */
  public SignedHttpRemoteDiscoveryCandidateClient(
      final NodeIdentityContext nodeIdentityContext,
      final ObjectMapper objectMapper,
      final String signatureAlgorithm) {
    if (nodeIdentityContext == null || objectMapper == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    if (signatureAlgorithm == null || signatureAlgorithm.isBlank()) {
      throw new IllegalArgumentException("signatureAlgorithm must not be blank");
    }
    this.nodeIdentityContext = nodeIdentityContext;
    this.objectMapper = objectMapper;
    this.signatureAlgorithm = signatureAlgorithm.trim();
    this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  }

  @Override
  public void upsertCandidate(final String baseUrl, final DiscoveryCandidateProfile profile) {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("baseUrl must not be blank");
    }
    if (profile == null) {
      throw new IllegalArgumentException("profile must not be null");
    }

    final String requestPath = BASE_PATH + profile.nodeId();
    final URI uri = URI.create(normalizeBaseUrl(baseUrl) + requestPath);
    final String nonce = "self-register-" + UUID.randomUUID();
    final long timestamp = Instant.now().getEpochSecond();
    final String canonicalPayload =
        RequestSignatureValidationService.buildCanonicalPayload(
            "PUT", requestPath, null, nonce, timestamp);
    final String signature =
        nodeIdentityContext.signBase64(
            signatureAlgorithm, canonicalPayload.getBytes(StandardCharsets.UTF_8));

    final Map<String, Object> body = new LinkedHashMap<>();
    body.put("failureDomain", profile.failureDomain());
    body.put("baseUrl", profile.baseUrl());
    body.put("originalRequestedBucket", profile.originalRequestedBucket());
    body.put("acceptedBuckets", profile.acceptedBuckets());

    final byte[] payloadBytes;
    try {
      payloadBytes = objectMapper.writeValueAsBytes(body);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(
          "Unable to serialize discovery candidate upsert payload", exception);
    }

    final HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header(RequestSignatureValidator.HEADER_NODE_ID, nodeIdentityContext.nodeId())
            .header(RequestSignatureValidator.HEADER_NONCE, nonce)
            .header(RequestSignatureValidator.HEADER_TIMESTAMP, String.valueOf(timestamp))
            .header(RequestSignatureValidator.HEADER_SIGNATURE_ALGORITHM, signatureAlgorithm)
            .header(RequestSignatureValidator.HEADER_SIGNATURE, signature)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(payloadBytes))
            .build();

    try {
      final HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "Self-register PUT failed with status "
                + response.statusCode()
                + " body="
                + response.body());
      }
      LOGGER
          .atDebug()
          .setMessage("Self-discovery upsert succeeded")
          .addKeyValue("baseUrl", baseUrl)
          .addKeyValue("nodeId", profile.nodeId())
          .addKeyValue("failureDomain", profile.failureDomain())
          .log();
    } catch (IOException exception) {
      throw new IllegalStateException("Self-register PUT to " + uri + " failed", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Self-register PUT to " + uri + " was interrupted", exception);
    }
  }

  private static String normalizeBaseUrl(final String baseUrl) {
    final String trimmed = baseUrl.trim();
    return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
  }
}
