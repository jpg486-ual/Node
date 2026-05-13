package es.ual.node.filesystem.adapters.out.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ual.node.filesystem.domain.FragmentPlacement;
import es.ual.node.filesystem.ports.out.RemoteFileManifestStorePort;
import es.ual.node.identitysecurity.adapters.in.web.RequestSignatureValidator;
import es.ual.node.identitysecurity.application.NodeIdentityContext;
import es.ual.node.identitysecurity.application.RequestSignatureValidationService;
import es.ual.node.negotiation.domain.FileManifest;
import es.ual.node.recovery.adapters.in.web.RecoveryStoreFileManifestPayload;
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

/**
 * Signed HTTP client used by the origin to replicate a file manifest to its tutor right after a
 * successful upload distribution.
 *
 * <p>POST {@code /recovery/file-manifests} replicates the manifest with the embedded {@code
 * clientPlacementsJson} blob. DELETE {@code /recovery/file-manifests/&#123;fileId&#125;} purges a
 * manifest when the file is deleted at origin
 *
 * <p>Both methods use the canonical 5-field signature {@code (method, path, query, nonce,
 * timestamp)} validated by {@link RequestSignatureValidator}.
 */
public class SignedHttpRemoteFileManifestStoreClient implements RemoteFileManifestStorePort {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(SignedHttpRemoteFileManifestStoreClient.class);
  private static final String BASE_PATH = "/recovery/file-manifests";
  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final NodeIdentityContext nodeIdentityContext;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final String signatureAlgorithm;

  /** Creates client. */
  public SignedHttpRemoteFileManifestStoreClient(
      final NodeIdentityContext nodeIdentityContext,
      final ObjectMapper objectMapper,
      final String signatureAlgorithm) {
    if (nodeIdentityContext == null) {
      throw new IllegalArgumentException("nodeIdentityContext must not be null");
    }
    if (objectMapper == null) {
      throw new IllegalArgumentException("objectMapper must not be null");
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
  public void store(
      final FileManifest manifest,
      final List<FragmentPlacement> placements,
      final String tutorBaseUrl) {
    if (manifest == null) {
      throw new IllegalArgumentException("manifest must not be null");
    }
    if (placements == null) {
      throw new IllegalArgumentException("placements must not be null");
    }
    if (tutorBaseUrl == null || tutorBaseUrl.isBlank()) {
      throw new IllegalArgumentException("tutorBaseUrl must not be blank");
    }

    final URI uri = URI.create(normalizeBaseUrl(tutorBaseUrl) + BASE_PATH);
    final String nonce = "manifest-store-" + UUID.randomUUID();
    final long timestamp = Instant.now().getEpochSecond();
    final String canonicalPayload =
        RequestSignatureValidationService.buildCanonicalPayload(
            "POST", BASE_PATH, null, nonce, timestamp);
    final String signature =
        nodeIdentityContext.signBase64(
            signatureAlgorithm, canonicalPayload.getBytes(StandardCharsets.UTF_8));
    final String requesterPublicKeyBase64 =
        Base64.getEncoder().encodeToString(nodeIdentityContext.publicKey().getEncoded());

    final RecoveryStoreFileManifestPayload body = new RecoveryStoreFileManifestPayload();
    body.setFileId(manifest.fileId());
    body.setRequesterNodeId(nodeIdentityContext.nodeId());
    body.setRequesterPublicKey(requesterPublicKeyBase64);
    body.setDirectoryPath(manifest.directoryPath());
    body.setOriginalFileName(manifest.originalFileName());
    body.setOriginalFileHash(manifest.originalFileHash());
    body.setOriginalSizeBytes(manifest.originalSizeBytes());
    body.setCompressedSizeBytes(manifest.compressedSizeBytes());
    body.setCompressionAlgorithm(manifest.compressionAlgorithm());
    body.setFragmentCount(manifest.fragmentCount());
    body.setFragmentSize(manifest.fragmentSize());
    body.setRedundancyN(manifest.redundancyN());
    body.setRedundancyK(manifest.redundancyK());
    body.setFragmentHashes(manifest.fragmentHashes());
    body.setClientPlacementsJson(serializePlacements(placements));
    body.setClientBlocksJson(serializeBlocks(manifest.blocks()));

    final String bodyJson = serializeBody(body);

    final HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .header(RequestSignatureValidator.HEADER_NODE_ID, nodeIdentityContext.nodeId())
            .header(RequestSignatureValidator.HEADER_NONCE, nonce)
            .header(RequestSignatureValidator.HEADER_TIMESTAMP, String.valueOf(timestamp))
            .header(RequestSignatureValidator.HEADER_SIGNATURE_ALGORITHM, signatureAlgorithm)
            .header(RequestSignatureValidator.HEADER_SIGNATURE, signature)
            .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
            .build();

    try {
      final HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "Manifest store failed with HTTP "
                + response.statusCode()
                + " body="
                + response.body());
      }
      LOGGER
          .atInfo()
          .setMessage("Manifest replicated to tutor")
          .addKeyValue("event", "MANIFEST_REPLICATION_SUCCESS")
          .addKeyValue("tutorBaseUrl", tutorBaseUrl)
          .addKeyValue("fileId", manifest.fileId())
          .addKeyValue("placementCount", placements.size())
          .log();
    } catch (IOException exception) {
      throw new IllegalStateException("Manifest store I/O error to " + uri, exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Manifest store interrupted to " + uri, exception);
    }
  }

  @Override
  public void delete(final String fileId, final String tutorBaseUrl) {
    if (fileId == null || fileId.isBlank()) {
      throw new IllegalArgumentException("fileId must not be blank");
    }
    if (tutorBaseUrl == null || tutorBaseUrl.isBlank()) {
      throw new IllegalArgumentException("tutorBaseUrl must not be blank");
    }

    final String requestPath = BASE_PATH + "/" + fileId.trim();
    final URI uri = URI.create(normalizeBaseUrl(tutorBaseUrl) + requestPath);
    final String nonce = "manifest-delete-" + UUID.randomUUID();
    final long timestamp = Instant.now().getEpochSecond();
    final String canonicalPayload =
        RequestSignatureValidationService.buildCanonicalPayload(
            "DELETE", requestPath, null, nonce, timestamp);
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
            .DELETE()
            .build();

    try {
      final HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      final int code = response.statusCode();
      if (code == 404) {
        LOGGER
            .atDebug()
            .setMessage("Manifest delete idempotent (already absent at tutor)")
            .addKeyValue("tutorBaseUrl", tutorBaseUrl)
            .addKeyValue("fileId", fileId)
            .log();
        return;
      }
      if (code < 200 || code >= 300) {
        throw new IllegalStateException(
            "Manifest delete failed with HTTP " + code + " body=" + response.body());
      }
      LOGGER
          .atInfo()
          .setMessage("Manifest deleted at tutor")
          .addKeyValue("tutorBaseUrl", tutorBaseUrl)
          .addKeyValue("fileId", fileId)
          .log();
    } catch (IOException exception) {
      throw new IllegalStateException("Manifest delete I/O error to " + uri, exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Manifest delete interrupted to " + uri, exception);
    }
  }

  @Override
  public void updatePath(
      final String fileId,
      final String newDirectoryPath,
      final String newOriginalFileName,
      final String tutorBaseUrl) {
    if (fileId == null || fileId.isBlank()) {
      throw new IllegalArgumentException("fileId must not be blank");
    }
    if (newDirectoryPath == null || newDirectoryPath.isBlank()) {
      throw new IllegalArgumentException("newDirectoryPath must not be blank");
    }
    if (newOriginalFileName == null || newOriginalFileName.isBlank()) {
      throw new IllegalArgumentException("newOriginalFileName must not be blank");
    }
    if (tutorBaseUrl == null || tutorBaseUrl.isBlank()) {
      throw new IllegalArgumentException("tutorBaseUrl must not be blank");
    }

    final String requestPath = BASE_PATH + "/" + fileId.trim();
    final URI uri = URI.create(normalizeBaseUrl(tutorBaseUrl) + requestPath);
    final String nonce = "manifest-update-" + UUID.randomUUID();
    final long timestamp = Instant.now().getEpochSecond();
    final String canonicalPayload =
        RequestSignatureValidationService.buildCanonicalPayload(
            "PATCH", requestPath, null, nonce, timestamp);
    final String signature =
        nodeIdentityContext.signBase64(
            signatureAlgorithm, canonicalPayload.getBytes(StandardCharsets.UTF_8));

    final String bodyJson =
        serializeUpdatePathBody(newDirectoryPath.trim(), newOriginalFileName.trim());

    final HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .header(RequestSignatureValidator.HEADER_NODE_ID, nodeIdentityContext.nodeId())
            .header(RequestSignatureValidator.HEADER_NONCE, nonce)
            .header(RequestSignatureValidator.HEADER_TIMESTAMP, String.valueOf(timestamp))
            .header(RequestSignatureValidator.HEADER_SIGNATURE_ALGORITHM, signatureAlgorithm)
            .header(RequestSignatureValidator.HEADER_SIGNATURE, signature)
            .method("PATCH", HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
            .build();

    try {
      final HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      final int code = response.statusCode();
      if (code < 200 || code >= 300) {
        throw new IllegalStateException(
            "Manifest path update failed with HTTP " + code + " body=" + response.body());
      }
      LOGGER
          .atInfo()
          .setMessage("Manifest path updated at tutor")
          .addKeyValue("event", "MANIFEST_PATH_UPDATED")
          .addKeyValue("tutorBaseUrl", tutorBaseUrl)
          .addKeyValue("fileId", fileId)
          .addKeyValue("newDirectoryPath", newDirectoryPath)
          .addKeyValue("newOriginalFileName", newOriginalFileName)
          .log();
    } catch (IOException exception) {
      throw new IllegalStateException("Manifest path update I/O error to " + uri, exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Manifest path update interrupted to " + uri, exception);
    }
  }

  private String serializeUpdatePathBody(
      final String newDirectoryPath, final String newOriginalFileName) {
    final java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("directoryPath", newDirectoryPath);
    body.put("originalFileName", newOriginalFileName);
    try {
      return objectMapper.writeValueAsString(body);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to serialize manifest update payload", ex);
    }
  }

  @Override
  public void updatePathBulk(final List<BulkUpdateEntry> entries, final String tutorBaseUrl) {
    if (entries == null || entries.isEmpty()) {
      throw new IllegalArgumentException("entries must not be empty");
    }
    if (tutorBaseUrl == null || tutorBaseUrl.isBlank()) {
      throw new IllegalArgumentException("tutorBaseUrl must not be blank");
    }
    for (BulkUpdateEntry e : entries) {
      if (e == null
          || e.fileId() == null
          || e.fileId().isBlank()
          || e.newDirectoryPath() == null
          || e.newDirectoryPath().isBlank()
          || e.newOriginalFileName() == null
          || e.newOriginalFileName().isBlank()) {
        throw new IllegalArgumentException("bulk entry must have non-blank fields");
      }
    }
    // Target collection lives on a sibling root (not a child segment of BASE_PATH) to
    // avoid the {fileId} path-variable.
    final String requestPath = BASE_PATH + "-bulk";
    final URI uri = URI.create(normalizeBaseUrl(tutorBaseUrl) + requestPath);
    final String nonce = "manifest-bulk-update-" + UUID.randomUUID();
    final long timestamp = Instant.now().getEpochSecond();
    final String canonicalPayload =
        RequestSignatureValidationService.buildCanonicalPayload(
            "PATCH", requestPath, null, nonce, timestamp);
    final String signature =
        nodeIdentityContext.signBase64(
            signatureAlgorithm, canonicalPayload.getBytes(StandardCharsets.UTF_8));
    final String bodyJson = serializeBulkBody(entries);
    final HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .header(RequestSignatureValidator.HEADER_NODE_ID, nodeIdentityContext.nodeId())
            .header(RequestSignatureValidator.HEADER_NONCE, nonce)
            .header(RequestSignatureValidator.HEADER_TIMESTAMP, String.valueOf(timestamp))
            .header(RequestSignatureValidator.HEADER_SIGNATURE_ALGORITHM, signatureAlgorithm)
            .header(RequestSignatureValidator.HEADER_SIGNATURE, signature)
            .method("PATCH", HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
            .build();
    try {
      final HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      final int code = response.statusCode();
      if (code < 200 || code >= 300) {
        throw new IllegalStateException(
            "Manifest bulk path update failed with HTTP " + code + " body=" + response.body());
      }
      LOGGER
          .atInfo()
          .setMessage("Manifest paths bulk-updated at tutor")
          .addKeyValue("event", "MANIFEST_PATH_UPDATED_BULK")
          .addKeyValue("tutorBaseUrl", tutorBaseUrl)
          .addKeyValue("count", entries.size())
          .log();
    } catch (IOException ex) {
      throw new IllegalStateException("Manifest bulk path update I/O error to " + uri, ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Manifest bulk path update interrupted to " + uri, ex);
    }
  }

  @Override
  public void deleteBulk(final List<String> fileIds, final String tutorBaseUrl) {
    if (fileIds == null || fileIds.isEmpty()) {
      throw new IllegalArgumentException("fileIds must not be empty");
    }
    if (tutorBaseUrl == null || tutorBaseUrl.isBlank()) {
      throw new IllegalArgumentException("tutorBaseUrl must not be blank");
    }
    for (String f : fileIds) {
      if (f == null || f.isBlank()) {
        throw new IllegalArgumentException("bulk delete fileId must not be blank");
      }
    }
    // Target collection is the same sibling root as the bulk so the
    // signed canonical path matches the controller's @RequestMapping. Java 11 HttpClient supports
    // DELETE with body via the generic .method(...) builder.
    final String requestPath = BASE_PATH + "-bulk";
    final URI uri = URI.create(normalizeBaseUrl(tutorBaseUrl) + requestPath);
    final String nonce = "manifest-bulk-delete-" + UUID.randomUUID();
    final long timestamp = Instant.now().getEpochSecond();
    final String canonicalPayload =
        RequestSignatureValidationService.buildCanonicalPayload(
            "DELETE", requestPath, null, nonce, timestamp);
    final String signature =
        nodeIdentityContext.signBase64(
            signatureAlgorithm, canonicalPayload.getBytes(StandardCharsets.UTF_8));
    final String bodyJson = serializeBulkDeleteBody(fileIds);
    final HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .header(RequestSignatureValidator.HEADER_NODE_ID, nodeIdentityContext.nodeId())
            .header(RequestSignatureValidator.HEADER_NONCE, nonce)
            .header(RequestSignatureValidator.HEADER_TIMESTAMP, String.valueOf(timestamp))
            .header(RequestSignatureValidator.HEADER_SIGNATURE_ALGORITHM, signatureAlgorithm)
            .header(RequestSignatureValidator.HEADER_SIGNATURE, signature)
            .method("DELETE", HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
            .build();
    try {
      final HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      final int code = response.statusCode();
      if (code < 200 || code >= 300) {
        throw new IllegalStateException(
            "Manifest bulk delete failed with HTTP " + code + " body=" + response.body());
      }
      LOGGER
          .atInfo()
          .setMessage("Manifests bulk-deleted at tutor")
          .addKeyValue("event", "MANIFEST_DELETED_BULK")
          .addKeyValue("tutorBaseUrl", tutorBaseUrl)
          .addKeyValue("count", fileIds.size())
          .log();
    } catch (IOException ex) {
      throw new IllegalStateException("Manifest bulk delete I/O error to " + uri, ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Manifest bulk delete interrupted to " + uri, ex);
    }
  }

  @Override
  public void checkTutorReachable(final String tutorBaseUrl) {
    if (tutorBaseUrl == null || tutorBaseUrl.isBlank()) {
      throw new IllegalArgumentException("tutorBaseUrl must not be blank");
    }
    // Preflight to /ops/system/health BEFORE distributing fragments to peers. Short
    // timeout (5s) since this is a fail-fast gate, if the tutor is too slow to answer health,
    // the user is better off retrying than waiting on an upload that may abort anyway.
    final String requestPath = "/ops/system/health";
    final URI uri = URI.create(normalizeBaseUrl(tutorBaseUrl) + requestPath);
    final String nonce = "tutor-health-" + UUID.randomUUID();
    final long timestamp = Instant.now().getEpochSecond();
    final String canonicalPayload =
        RequestSignatureValidationService.buildCanonicalPayload(
            "GET", requestPath, null, nonce, timestamp);
    final String signature =
        nodeIdentityContext.signBase64(
            signatureAlgorithm, canonicalPayload.getBytes(StandardCharsets.UTF_8));
    final HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(java.time.Duration.ofSeconds(5))
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
      final int code = response.statusCode();
      if (code < 200 || code >= 300) {
        throw new IllegalStateException(
            "Tutor health check failed with HTTP " + code + " body=" + response.body());
      }
    } catch (IOException ex) {
      throw new IllegalStateException("Tutor health check I/O error to " + uri, ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Tutor health check interrupted to " + uri, ex);
    }
  }

  private String serializeBulkDeleteBody(final List<String> fileIds) {
    final java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("fileIds", fileIds.stream().map(String::trim).toList());
    try {
      return objectMapper.writeValueAsString(body);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to serialize bulk delete payload", ex);
    }
  }

  private String serializeBulkBody(final List<BulkUpdateEntry> entries) {
    final java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
    final java.util.List<java.util.LinkedHashMap<String, Object>> arr = new java.util.ArrayList<>();
    for (BulkUpdateEntry e : entries) {
      final java.util.LinkedHashMap<String, Object> item = new java.util.LinkedHashMap<>();
      item.put("fileId", e.fileId().trim());
      item.put("directoryPath", e.newDirectoryPath().trim());
      item.put("originalFileName", e.newOriginalFileName().trim());
      arr.add(item);
    }
    body.put("entries", arr);
    try {
      return objectMapper.writeValueAsString(body);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to serialize bulk manifest update payload", ex);
    }
  }

  private String serializePlacements(final List<FragmentPlacement> placements) {
    if (placements.isEmpty()) {
      return "[]";
    }
    try {
      return objectMapper.writeValueAsString(placements);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to serialize fragment placements", ex);
    }
  }

  /**
   * Serializa la lista de {@link es.ual.node.negotiation.domain.BlockManifest} en JSON para
   * almacenarla como {@code clientBlocksJson} en el tutor. Devuelve {@code null} para manifests
   * legacy single-block (lista vacía), el tutor persiste NULL y el restore cae al
   * synthetic-single-block path.
   */
  private String serializeBlocks(final List<es.ual.node.negotiation.domain.BlockManifest> blocks) {
    if (blocks == null || blocks.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(blocks);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to serialize block manifests", ex);
    }
  }

  private String serializeBody(final RecoveryStoreFileManifestPayload body) {
    try {
      return objectMapper.writeValueAsString(body);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to serialize manifest store payload", ex);
    }
  }

  private static String normalizeBaseUrl(final String baseUrl) {
    final String trimmed = baseUrl.trim();
    return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
  }
}
