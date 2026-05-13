package es.ual.node.filesystem.adapters.out.http;

import es.ual.node.filesystem.application.CustodianInsufficientStorageException;
import es.ual.node.filesystem.ports.out.RemoteFragmentDistributionClientPort;
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
import java.util.Base64;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Signed HTTP client for the custody endpoint. Issues PUT/POST {@code /custody/fragments} (deposit)
 * and GET {@code /custody/fragments/&#123;id&#125;/content} (fetch) with the canonical signature
 * scheme {@code (method, path, query, nonce, timestamp)} validated by {@link
 * RequestSignatureValidator}.
 */
public class SignedHttpRemoteFragmentDistributionClient
    implements RemoteFragmentDistributionClientPort {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(SignedHttpRemoteFragmentDistributionClient.class);
  private static final String BASE_PATH = "/custody/fragments";
  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final NodeIdentityContext nodeIdentityContext;
  private final HttpClient httpClient;
  private final String signatureAlgorithm;

  /** Creates client. */
  public SignedHttpRemoteFragmentDistributionClient(
      final NodeIdentityContext nodeIdentityContext, final String signatureAlgorithm) {
    if (nodeIdentityContext == null) {
      throw new IllegalArgumentException("nodeIdentityContext must not be null");
    }
    if (signatureAlgorithm == null || signatureAlgorithm.isBlank()) {
      throw new IllegalArgumentException("signatureAlgorithm must not be blank");
    }
    this.nodeIdentityContext = nodeIdentityContext;
    this.signatureAlgorithm = signatureAlgorithm.trim();
    this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  }

  @Override
  public void storeFragment(
      final String custodianBaseUrl,
      final String fragmentId,
      final String agreementId,
      final byte[] payload,
      final String checksumAlgorithm,
      final String checksumHex,
      final Long custodySeconds) {
    if (custodianBaseUrl == null || custodianBaseUrl.isBlank()) {
      throw new IllegalArgumentException("custodianBaseUrl must not be blank");
    }
    if (fragmentId == null || fragmentId.isBlank()) {
      throw new IllegalArgumentException("fragmentId must not be blank");
    }
    if (payload == null || payload.length == 0) {
      throw new IllegalArgumentException("payload must not be empty");
    }

    final URI uri = URI.create(normalizeBaseUrl(custodianBaseUrl) + BASE_PATH);
    final String nonce = "custody-store-" + UUID.randomUUID();
    final long timestamp = Instant.now().getEpochSecond();
    final String canonicalPayload =
        RequestSignatureValidationService.buildCanonicalPayload(
            "POST", BASE_PATH, null, nonce, timestamp);
    final String signature =
        nodeIdentityContext.signBase64(
            signatureAlgorithm, canonicalPayload.getBytes(StandardCharsets.UTF_8));
    final String senderPublicKeyBase64 =
        Base64.getEncoder().encodeToString(nodeIdentityContext.publicKey().getEncoded());

    final HttpRequest.Builder builder =
        HttpRequest.newBuilder(uri)
            .timeout(TIMEOUT)
            .header("Content-Type", "application/octet-stream")
            .header(RequestSignatureValidator.HEADER_NODE_ID, nodeIdentityContext.nodeId())
            .header(RequestSignatureValidator.HEADER_NONCE, nonce)
            .header(RequestSignatureValidator.HEADER_TIMESTAMP, String.valueOf(timestamp))
            .header(RequestSignatureValidator.HEADER_SIGNATURE_ALGORITHM, signatureAlgorithm)
            .header(RequestSignatureValidator.HEADER_SIGNATURE, signature)
            .header("X-Fragment-Id", fragmentId)
            .header("X-Agreement-Id", agreementId)
            .header("X-Sender-Node-Id", nodeIdentityContext.nodeId())
            .header("X-Sender-Public-Key", senderPublicKeyBase64)
            .header("X-Checksum-Algorithm", checksumAlgorithm)
            .header("X-Checksum", checksumHex);
    if (custodySeconds != null) {
      builder.header("X-Custody-Seconds", String.valueOf(custodySeconds));
    }
    builder.POST(HttpRequest.BodyPublishers.ofByteArray(payload));

    try {
      final HttpResponse<String> response =
          httpClient.send(
              builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() == 507) {
        throw new CustodianInsufficientStorageException(
            custodianBaseUrl,
            "Custodian "
                + custodianBaseUrl
                + " rejected fragment "
                + fragmentId
                + " with 507 Insufficient Storage: "
                + response.body());
      }
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "Custody store failed with HTTP " + response.statusCode() + " body=" + response.body());
      }
      LOGGER
          .atDebug()
          .setMessage("Custody store succeeded")
          .addKeyValue("custodianBaseUrl", custodianBaseUrl)
          .addKeyValue("fragmentId", fragmentId)
          .log();
    } catch (IOException exception) {
      throw new IllegalStateException("Custody store I/O error to " + uri, exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Custody store interrupted to " + uri, exception);
    }
  }

  @Override
  public byte[] fetchFragment(final String custodianBaseUrl, final String fragmentId) {
    if (custodianBaseUrl == null || custodianBaseUrl.isBlank()) {
      throw new IllegalArgumentException("custodianBaseUrl must not be blank");
    }
    if (fragmentId == null || fragmentId.isBlank()) {
      throw new IllegalArgumentException("fragmentId must not be blank");
    }

    final String requestPath = BASE_PATH + "/" + fragmentId + "/content";
    final URI uri = URI.create(normalizeBaseUrl(custodianBaseUrl) + requestPath);
    final String nonce = "custody-fetch-" + UUID.randomUUID();
    final long timestamp = Instant.now().getEpochSecond();
    final String canonicalPayload =
        RequestSignatureValidationService.buildCanonicalPayload(
            "GET", requestPath, null, nonce, timestamp);
    final String signature =
        nodeIdentityContext.signBase64(
            signatureAlgorithm, canonicalPayload.getBytes(StandardCharsets.UTF_8));

    final HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(TIMEOUT)
            .header("Accept", "application/octet-stream")
            .header(RequestSignatureValidator.HEADER_NODE_ID, nodeIdentityContext.nodeId())
            .header(RequestSignatureValidator.HEADER_NONCE, nonce)
            .header(RequestSignatureValidator.HEADER_TIMESTAMP, String.valueOf(timestamp))
            .header(RequestSignatureValidator.HEADER_SIGNATURE_ALGORITHM, signatureAlgorithm)
            .header(RequestSignatureValidator.HEADER_SIGNATURE, signature)
            .GET()
            .build();

    try {
      final HttpResponse<byte[]> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException("Custody fetch failed with HTTP " + response.statusCode());
      }
      return response.body();
    } catch (IOException exception) {
      throw new IllegalStateException("Custody fetch I/O error from " + uri, exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Custody fetch interrupted from " + uri, exception);
    }
  }

  private static String normalizeBaseUrl(final String baseUrl) {
    final String trimmed = baseUrl.trim();
    return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
  }
}
