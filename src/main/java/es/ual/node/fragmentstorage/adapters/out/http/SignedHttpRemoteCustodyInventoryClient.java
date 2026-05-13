package es.ual.node.fragmentstorage.adapters.out.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ual.node.fragmentstorage.adapters.in.web.CustodyInventoryListPayload;
import es.ual.node.fragmentstorage.domain.CustodyInventoryItem;
import es.ual.node.fragmentstorage.ports.out.RemoteCustodyInventoryClientPort;
import es.ual.node.identitysecurity.adapters.in.web.RequestSignatureValidator;
import es.ual.node.identitysecurity.application.NodeIdentityContext;
import es.ual.node.identitysecurity.application.RequestSignatureValidationService;
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
 * Signed HTTP client for {@code GET /custody/fragments/by-requester/{nodeId}}. Used by {@code
 * NodeFsRecoveryWorker} to pull the per-custodian inventory of fragments during lazy/active
 * recovery.
 */
public class SignedHttpRemoteCustodyInventoryClient implements RemoteCustodyInventoryClientPort {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(SignedHttpRemoteCustodyInventoryClient.class);
  private static final String PATH_PREFIX = "/custody/fragments/by-requester/";
  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final NodeIdentityContext nodeIdentityContext;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final String signatureAlgorithm;

  /** Creates client. */
  public SignedHttpRemoteCustodyInventoryClient(
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
  public List<CustodyInventoryItem> fetchInventory(
      final String custodianBaseUrl, final String nodeId) {
    if (custodianBaseUrl == null || custodianBaseUrl.isBlank()) {
      return List.of();
    }
    if (nodeId == null || nodeId.isBlank()) {
      throw new IllegalArgumentException("nodeId must not be blank");
    }
    final String path = PATH_PREFIX + nodeId.trim();
    final URI uri = URI.create(normalizeBaseUrl(custodianBaseUrl) + path);
    final String nonce = "inventory-" + UUID.randomUUID();
    final long timestamp = Instant.now().getEpochSecond();
    final String canonical =
        RequestSignatureValidationService.buildCanonicalPayload(
            "GET", path, null, nonce, timestamp);
    final String signature =
        nodeIdentityContext.signBase64(
            signatureAlgorithm, canonical.getBytes(StandardCharsets.UTF_8));

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
        LOGGER
            .atWarn()
            .setMessage("Custody inventory pull failed (non-2xx)")
            .addKeyValue("custodianBaseUrl", custodianBaseUrl)
            .addKeyValue("statusCode", response.statusCode())
            .log();
        return List.of();
      }
      final CustodyInventoryListPayload payload =
          objectMapper.readValue(response.body(), CustodyInventoryListPayload.class);
      if (payload == null || payload.fragments() == null) {
        return List.of();
      }
      return payload.fragments().stream()
          .map(
              p ->
                  new CustodyInventoryItem(
                      p.fragmentId(),
                      p.agreementId(),
                      p.sizeBytes(),
                      p.checksum(),
                      p.expiresAt(),
                      p.ttlRemainingSeconds()))
          .toList();
    } catch (Exception ex) {
      LOGGER
          .atWarn()
          .setMessage("Custody inventory pull errored")
          .addKeyValue("custodianBaseUrl", custodianBaseUrl)
          .addKeyValue("error", ex.getMessage())
          .log();
      return List.of();
    }
  }

  private static String normalizeBaseUrl(final String baseUrl) {
    return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }
}
