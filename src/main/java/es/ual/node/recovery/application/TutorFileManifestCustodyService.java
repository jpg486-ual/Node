package es.ual.node.recovery.application;

import es.ual.node.bootstrap.configuration.NodeTopologyProperties;
import es.ual.node.recovery.domain.CustodiedFileManifest;
import es.ual.node.recovery.ports.out.CustodiedFileManifestPort;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tutor-side service for proactive custody of {@link es.ual.node.negotiation.domain.FileManifest}.
 * Authenticates requests by matching {@code requesterPublicKey} against {@code
 * node.topology.tutorAcceptedPublicKeys}.
 */
public class TutorFileManifestCustodyService {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(TutorFileManifestCustodyService.class);

  // Same regex as FileManifest.DIRECTORY_PATH. Duplicated here because
  // FileManifest's pattern is private and reusing the full FileManifest constructor for path
  // validation would require dummy values for all 13 fields.
  private static final Pattern DIRECTORY_PATH_PATTERN =
      Pattern.compile("^/(?:[^\\u0000-\\u001f/\\\\]+(?:/[^\\u0000-\\u001f/\\\\]+)*)?$");

  private final CustodiedFileManifestPort port;
  private final Clock clock;
  private final Set<String> allowedPublicKeys;

  /**
   * Creates service.
   *
   * @param topologyProperties topology properties (whitelist of accepted requester keys)
   * @param port custody port
   * @param clock clock
   */
  public TutorFileManifestCustodyService(
      final NodeTopologyProperties topologyProperties,
      final CustodiedFileManifestPort port,
      final Clock clock) {
    if (topologyProperties == null || port == null || clock == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    this.port = port;
    this.clock = clock;
    this.allowedPublicKeys =
        topologyProperties.getTutorAcceptedPublicKeys().stream()
            .filter(v -> v != null && !v.isBlank())
            .map(String::trim)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  /**
   * Persists a manifest under tutor custody. Replaces existing entry by {@code fileId}.
   *
   * @param request store request
   * @return persisted custody record
   */
  @Transactional
  public CustodiedFileManifest store(final StoreFileManifestRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }
    if (!allowedPublicKeys.contains(request.requesterPublicKey())) {
      throw new SecurityException("requester public key is not accepted by tutor");
    }

    final Instant now = Instant.now(clock);

    final CustodiedFileManifest custody =
        new CustodiedFileManifest(
            request.fileId(),
            request.requesterNodeId(),
            request.requesterPublicKey(),
            request.directoryPath(),
            request.originalFileName(),
            request.originalFileHash(),
            request.originalSizeBytes(),
            request.compressedSizeBytes(),
            request.compressionAlgorithm(),
            request.fragmentCount(),
            request.fragmentSize(),
            request.redundancyN(),
            request.redundancyK(),
            request.fragmentHashes(),
            request.clientPlacementsJson(),
            request.clientBlocksJson(),
            now);
    port.save(custody);

    LOGGER
        .atInfo()
        .setMessage("FileManifest custodied by tutor")
        .addKeyValue("fileId", custody.fileId())
        .addKeyValue("requesterNodeId", custody.requesterNodeId())
        .addKeyValue("directoryPath", custody.directoryPath())
        .log();

    return custody;
  }

  /**
   * Updates the {@code directoryPath} and {@code originalFileName} of a custodied manifest. Invoked
   * by the origin via signed {@code PATCH /recovery/file-manifests/&#123;fileId&#125;} when a
   * {@code FsEntry} is renamed or moved at origin so the tutor's view reflects the current FS
   * state, without this, restore reconstructs the FS with the {@code directoryPath} frozen at
   * upload time.
   *
   * <p>Authorisation: the {@code callerNodeId} (extracted from the signed request's {@code
   * X-Node-Id} header) must match the manifest's {@code requesterNodeId}. A node cannot patch
   * another node's manifests.
   *
   * <p>Errors:
   *
   * <ul>
   *   <li>{@link IllegalArgumentException} when {@code fileId}, {@code callerNodeId}, {@code
   *       directoryPath} or {@code originalFileName} are blank or malformed.
   *   <li>{@link NoSuchElementException} when no manifest exists for {@code fileId}.
   *   <li>{@link SecurityException} when {@code callerNodeId} does not own the manifest.
   * </ul>
   *
   * @param fileId manifest identifier
   * @param callerNodeId node id of the caller (from signature)
   * @param directoryPath new path (must match the same regex as the original)
   * @param originalFileName new file name (no path separators)
   * @return updated manifest
   */
  @Transactional
  public CustodiedFileManifest updatePath(
      final String fileId,
      final String callerNodeId,
      final String directoryPath,
      final String originalFileName) {
    if (fileId == null || fileId.isBlank()) {
      throw new IllegalArgumentException("fileId must not be blank");
    }
    if (callerNodeId == null || callerNodeId.isBlank()) {
      throw new IllegalArgumentException("callerNodeId must not be blank");
    }
    if (directoryPath == null || directoryPath.isBlank()) {
      throw new IllegalArgumentException("directoryPath must not be blank");
    }
    if (!DIRECTORY_PATH_PATTERN.matcher(directoryPath.trim()).matches()) {
      throw new IllegalArgumentException(
          "directoryPath must start with '/' and contain only valid path segments");
    }
    if (originalFileName == null || originalFileName.isBlank()) {
      throw new IllegalArgumentException("originalFileName must not be blank");
    }
    if (originalFileName.contains("/")) {
      throw new IllegalArgumentException("originalFileName must not contain path separators");
    }
    final String normalized = fileId.trim();
    final String caller = callerNodeId.trim();
    final CustodiedFileManifest existing =
        port.findByFileId(normalized)
            .orElseThrow(() -> new NoSuchElementException("manifest not found: " + normalized));
    if (!existing.requesterNodeId().equals(caller)) {
      throw new SecurityException("caller " + caller + " does not own manifest " + normalized);
    }
    final String newDirectoryPath = directoryPath.trim();
    final String newOriginalFileName = originalFileName.trim();
    final String oldDirectoryPath = existing.directoryPath();
    final String oldOriginalFileName = existing.originalFileName();
    final CustodiedFileManifest updated =
        new CustodiedFileManifest(
            existing.fileId(),
            existing.requesterNodeId(),
            existing.requesterPublicKey(),
            newDirectoryPath,
            newOriginalFileName,
            existing.originalFileHash(),
            existing.originalSizeBytes(),
            existing.compressedSizeBytes(),
            existing.compressionAlgorithm(),
            existing.fragmentCount(),
            existing.fragmentSize(),
            existing.redundancyN(),
            existing.redundancyK(),
            existing.fragmentHashes(),
            existing.clientPlacementsJson(),
            existing.clientBlocksJson(),
            existing.storedAt(),
            existing.lastSupervisedCheckAt(),
            existing.consecutiveOriginFailures());
    port.save(updated);
    LOGGER
        .atInfo()
        .setMessage("FileManifest path updated by tutor")
        .addKeyValue("event", "MANIFEST_PATH_UPDATED")
        .addKeyValue("fileId", normalized)
        .addKeyValue("requesterNodeId", caller)
        .addKeyValue("oldDirectoryPath", oldDirectoryPath)
        .addKeyValue("newDirectoryPath", newDirectoryPath)
        .addKeyValue("oldOriginalFileName", oldOriginalFileName)
        .addKeyValue("newOriginalFileName", newOriginalFileName)
        .log();
    return updated;
  }

  /**
   * Bulk variant of {@link #updatePath}. Applies N metadata updates atomically in a single
   * transaction: the tutor pre-validates ALL entries (regex of paths, ownership of each fileId,
   * manifest existence) and only then iterates the writes. If anything fails during pre-validation,
   * NO write is performed, fail-fast all-or-nothing semantics.
   *
   * @param callerNodeId node id of the caller (from signature)
   * @param entries non-empty list of bulk update entries
   * @return list of updated manifests in the same order as the input entries
   */
  @Transactional
  public List<CustodiedFileManifest> updatePathBulk(
      final String callerNodeId, final List<BulkUpdateEntry> entries) {
    if (callerNodeId == null || callerNodeId.isBlank()) {
      throw new IllegalArgumentException("callerNodeId must not be blank");
    }
    if (entries == null || entries.isEmpty()) {
      throw new IllegalArgumentException("entries must not be empty");
    }
    final String caller = callerNodeId.trim();

    final java.util.List<CustodiedFileManifest> existingByIndex = new java.util.ArrayList<>();
    for (BulkUpdateEntry e : entries) {
      if (e == null) {
        throw new IllegalArgumentException("bulk entry must not be null");
      }
      if (e.fileId() == null || e.fileId().isBlank()) {
        throw new IllegalArgumentException("bulk entry fileId must not be blank");
      }
      if (e.directoryPath() == null || e.directoryPath().isBlank()) {
        throw new IllegalArgumentException(
            "bulk entry directoryPath must not be blank for fileId=" + e.fileId());
      }
      if (!DIRECTORY_PATH_PATTERN.matcher(e.directoryPath().trim()).matches()) {
        throw new IllegalArgumentException(
            "bulk entry directoryPath must start with '/' and contain only valid path segments "
                + "for fileId="
                + e.fileId());
      }
      if (e.originalFileName() == null || e.originalFileName().isBlank()) {
        throw new IllegalArgumentException(
            "bulk entry originalFileName must not be blank for fileId=" + e.fileId());
      }
      if (e.originalFileName().contains("/")) {
        throw new IllegalArgumentException(
            "bulk entry originalFileName must not contain path separators for fileId="
                + e.fileId());
      }
      final String normalized = e.fileId().trim();
      final CustodiedFileManifest existing =
          port.findByFileId(normalized)
              .orElseThrow(() -> new NoSuchElementException("manifest not found: " + normalized));
      if (!existing.requesterNodeId().equals(caller)) {
        throw new SecurityException("caller " + caller + " does not own manifest " + normalized);
      }
      existingByIndex.add(existing);
    }

    final java.util.List<CustodiedFileManifest> updated = new java.util.ArrayList<>();
    for (int i = 0; i < entries.size(); i++) {
      final BulkUpdateEntry e = entries.get(i);
      final CustodiedFileManifest existing = existingByIndex.get(i);
      final CustodiedFileManifest fresh =
          new CustodiedFileManifest(
              existing.fileId(),
              existing.requesterNodeId(),
              existing.requesterPublicKey(),
              e.directoryPath().trim(),
              e.originalFileName().trim(),
              existing.originalFileHash(),
              existing.originalSizeBytes(),
              existing.compressedSizeBytes(),
              existing.compressionAlgorithm(),
              existing.fragmentCount(),
              existing.fragmentSize(),
              existing.redundancyN(),
              existing.redundancyK(),
              existing.fragmentHashes(),
              existing.clientPlacementsJson(),
              existing.clientBlocksJson(),
              existing.storedAt(),
              existing.lastSupervisedCheckAt(),
              existing.consecutiveOriginFailures());
      port.save(fresh);
      updated.add(fresh);
    }

    LOGGER
        .atInfo()
        .setMessage("FileManifest path bulk-updated by tutor")
        .addKeyValue("event", "MANIFEST_PATH_UPDATED_BULK")
        .addKeyValue("requesterNodeId", caller)
        .addKeyValue("count", entries.size())
        .addKeyValue("firstFileId", entries.get(0).fileId())
        .addKeyValue("lastFileId", entries.get(entries.size() - 1).fileId())
        .log();
    return updated;
  }

  /** Bulk update entry payload for {@link #updatePathBulk}. */
  public record BulkUpdateEntry(String fileId, String directoryPath, String originalFileName) {}

  /**
   * Bulk variant of {@link #delete}. Removes N manifests in a single transaction. Idempotent for
   * missing {@code fileId}s. They are counted in {@link DeleteBulkResult#missingCount()} but do not
   * abort the operation, mirroring the single-delete semantics that treat absent rows as success.
   *
   * @param callerNodeId node id of the caller (from signature)
   * @param fileIds non-empty list of manifest identifiers
   * @return result with deleted count and missing count
   */
  @Transactional
  public DeleteBulkResult deleteBulk(final String callerNodeId, final List<String> fileIds) {
    if (callerNodeId == null || callerNodeId.isBlank()) {
      throw new IllegalArgumentException("callerNodeId must not be blank");
    }
    if (fileIds == null || fileIds.isEmpty()) {
      throw new IllegalArgumentException("fileIds must not be empty");
    }
    final String caller = callerNodeId.trim();

    final java.util.List<String> ownedExisting = new java.util.ArrayList<>();
    int missing = 0;
    for (String raw : fileIds) {
      if (raw == null || raw.isBlank()) {
        throw new IllegalArgumentException("bulk delete fileId must not be blank");
      }
      final String normalized = raw.trim();
      final java.util.Optional<CustodiedFileManifest> existing = port.findByFileId(normalized);
      if (existing.isEmpty()) {
        missing++;
        continue;
      }
      if (!existing.get().requesterNodeId().equals(caller)) {
        throw new SecurityException("caller " + caller + " does not own manifest " + normalized);
      }
      ownedExisting.add(normalized);
    }

    int deleted = 0;
    for (String normalized : ownedExisting) {
      if (port.deleteByFileId(normalized)) {
        deleted++;
      }
    }

    LOGGER
        .atInfo()
        .setMessage("FileManifests bulk-deleted by tutor")
        .addKeyValue("event", "MANIFEST_DELETED_BULK")
        .addKeyValue("requesterNodeId", caller)
        .addKeyValue("requestedCount", fileIds.size())
        .addKeyValue("deletedCount", deleted)
        .addKeyValue("missingCount", missing)
        .log();
    return new DeleteBulkResult(deleted, missing);
  }

  /** Bulk delete result payload. */
  public record DeleteBulkResult(int deletedCount, int missingCount) {}

  /**
   * Removes a manifest from tutor custody. Invoked by the origin via signed {@code DELETE
   * /recovery/file-manifests/&#123;fileId&#125;} when a file is deleted at origin so the tutor copy
   * stops occupying space.
   *
   * <p>Authorisation: the {@code callerNodeId} (extracted from the signed request's {@code
   * X-Node-Id} header) must match the manifest's {@code requesterNodeId}. A node cannot delete
   * another node's manifests. Idempotent: repeated deletes for an absent {@code fileId} return
   * {@code false} without raising.
   *
   * @param fileId manifest identifier
   * @param callerNodeId node id of the caller (from signature)
   * @return {@code true} if a row was deleted; {@code false} when the row was already absent
   */
  @Transactional
  public boolean delete(final String fileId, final String callerNodeId) {
    if (fileId == null || fileId.isBlank()) {
      throw new IllegalArgumentException("fileId must not be blank");
    }
    if (callerNodeId == null || callerNodeId.isBlank()) {
      throw new IllegalArgumentException("callerNodeId must not be blank");
    }
    final String normalized = fileId.trim();
    final String caller = callerNodeId.trim();
    final CustodiedFileManifest existing =
        port.findByFileId(normalized)
            .orElseThrow(() -> new NoSuchElementException("manifest not found: " + normalized));
    if (!existing.requesterNodeId().equals(caller)) {
      throw new SecurityException("caller " + caller + " does not own manifest " + normalized);
    }
    final boolean deleted = port.deleteByFileId(normalized);
    LOGGER
        .atInfo()
        .setMessage("FileManifest custody deleted")
        .addKeyValue("event", "MANIFEST_CUSTODY_DELETED")
        .addKeyValue("fileId", normalized)
        .addKeyValue("callerNodeId", caller)
        .addKeyValue("deleted", deleted)
        .log();
    return deleted;
  }

  /**
   * Returns manifests custodied for the given requester node id.
   *
   * @param requesterNodeId requester node id
   * @return list of manifests, ordered by storedAt desc
   */
  @Transactional(readOnly = true)
  public List<CustodiedFileManifest> listByRequesterNodeId(final String requesterNodeId) {
    if (requesterNodeId == null || requesterNodeId.isBlank()) {
      return List.of();
    }
    return port.findByRequesterNodeId(requesterNodeId.trim());
  }

  /** Request payload for {@link #store(StoreFileManifestRequest)}. */
  public record StoreFileManifestRequest(
      String fileId,
      String requesterNodeId,
      String requesterPublicKey,
      String directoryPath,
      String originalFileName,
      String originalFileHash,
      long originalSizeBytes,
      Long compressedSizeBytes,
      String compressionAlgorithm,
      int fragmentCount,
      long fragmentSize,
      int redundancyN,
      int redundancyK,
      List<String> fragmentHashes,
      String clientPlacementsJson,
      String clientBlocksJson) {}
}
