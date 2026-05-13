package es.ual.node.filesystem.application;

import es.ual.node.filesystem.domain.FsEntry;
import es.ual.node.filesystem.domain.FsEntryType;
import es.ual.node.filesystem.ports.out.FsEntryPort;
import es.ual.node.filesystem.ports.out.FsFileContentPort;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Application service for user file-content upload/download.
 *
 * <p>Two operating modes coexist:
 *
 * <ul>
 *   <li><b>Distributed mode</b> ({@link FileContentDistributionService} present): upload encodes
 *       Reed-Solomon, distributes fragments across cluster custodians, and persists a manifest;
 *       download reconstructs from {@code k} of {@code n} fragments. The local blob adapter is not
 *       touched.
 *   <li><b>Legacy local-blob mode</b> (orchestrator absent, dev / minimal wiring): the original
 *       {@link FsFileContentPort} is used to store and retrieve the entire file as a single blob.
 *       Only valid for development without the cluster.
 * </ul>
 *
 * <p>Mode selection is by bean availability: the orchestrator is gated by {@code
 * node.filesystem.distribution.enabled=true}.
 */
public class FileContentService {

  private static final Logger LOGGER = LoggerFactory.getLogger(FileContentService.class);

  private final FsEntryPort fsEntryPort;
  private final FsFileContentPort fsFileContentPort;
  private final FileContentDistributionService distributionService;

  /**
   * Creates service in legacy local-blob mode (dev only, distribution disabled).
   *
   * @param fsEntryPort filesystem metadata persistence
   * @param fsFileContentPort filesystem content persistence
   */
  public FileContentService(
      final FsEntryPort fsEntryPort, final FsFileContentPort fsFileContentPort) {
    this(fsEntryPort, fsFileContentPort, null);
  }

  /**
   * Creates service. When {@code distributionService} is non-null the upload/download flow
   * delegates to it (Reed-Solomon fragmentation + cluster distribution + manifest reconstruction).
   *
   * @param fsEntryPort filesystem metadata persistence
   * @param fsFileContentPort filesystem content persistence (legacy fallback)
   * @param distributionService optional Reed-Solomon orchestrator; null in dev/minimal setups
   *     disables distribution
   */
  public FileContentService(
      final FsEntryPort fsEntryPort,
      final FsFileContentPort fsFileContentPort,
      final FileContentDistributionService distributionService) {
    if (fsEntryPort == null || fsFileContentPort == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    this.fsEntryPort = fsEntryPort;
    this.fsFileContentPort = fsFileContentPort;
    this.distributionService = distributionService;
  }

  /**
   * Source the {@link #preflightDownload} resolved for the requested entry. The controller uses
   * this to commit response headers (Content-Type, Content-Length, X-Content-SHA256) ONLY when the
   * download is going to succeed, avoiding the half-committed-response trap that caused {@code
   * HttpMessageNotWritableException} → spurious 401.
   */
  public enum DownloadSource {
    PEERS_RECONSTRUCT,
    LOCAL_BLOB
  }

  /**
   * Preflight a download. Validates that either the distributed reconstruct path or the local blob
   * fallback can produce the bytes, WITHOUT committing the response or fetching from peers. Lets
   * the controller pick the right error status before any header is written.
   *
   * @param username username owner
   * @param entryId entry id
   * @return source the controller will use to materialise bytes
   * @throws NoSuchElementException entry not found / not a file / deleted
   * @throws InconsistentFragmentPlacementException placements are corrupt (5xx)
   * @throws FileIrrecoverableException reconstruct failed and no local blob is available
   */
  public DownloadSource preflightDownload(final String username, final String entryId) {
    final String normalizedUsername = normalizeNonBlank(username, "username");
    final String normalizedEntryId = normalizeNonBlank(entryId, "entryId");
    final FsEntry entry = requireActiveFileEntry(normalizedUsername, normalizedEntryId);

    final boolean canTryPeers =
        distributionService != null && entry.fileId() != null && !entry.fileId().isBlank();
    if (canTryPeers) {
      // Con distribución activa el path canónico es reconstruct distribuido. El blob
      // local fallback queda DESACTIVADO en este modo: el modelo arquitectural cardinal del
      // proyecto (fragments-only nodes) prohíbe que un nodo origen sirva un archivo completo desde
      // disco. Si el reconstruct falla o no hay ≥k custodians, surfaceamos
      // FileIrrecoverableException
      // / InconsistentFragmentPlacementException sin tocar el blob local.
      try {
        distributionService.validateReconstructable(entry.fileId());
        if (distributionService.canReachEnoughCustodians(entry.fileId())) {
          return DownloadSource.PEERS_RECONSTRUCT;
        }
        throw new FileIrrecoverableException(entry.fileId(), null);
      } catch (NoSuchElementException ex) {
        // Manifest missing: catalog tampered or missing. Sin fallback, irrecuperable.
        throw new FileIrrecoverableException(entry.fileId(), ex);
      }
    }
    // Legacy local-blob path (distribution.enabled=false, dev/minimal setup): the blob is the
    // primary store, not a fallback.
    if (fsFileContentPort.find(normalizedUsername, normalizedEntryId).isPresent()) {
      return DownloadSource.LOCAL_BLOB;
    }
    throw new NoSuchElementException("file content not found");
  }

  /**
   * Stores content for a user entry validating checksum and size against metadata.
   *
   * @param username username owner
   * @param entryId entry id
   * @param content binary payload
   * @return upload result
   */
  public FileContentUploadResult upload(
      final String username, final String entryId, final byte[] content) {
    final String normalizedUsername = normalizeNonBlank(username, "username");
    final String normalizedEntryId = normalizeNonBlank(entryId, "entryId");
    if (content == null) {
      throw new IllegalArgumentException("content must not be null");
    }

    final FsEntry entry = requireActiveFileEntry(normalizedUsername, normalizedEntryId);
    if (entry.sizeBytes() != content.length) {
      throw new FsContentConflictException("uploaded content size does not match metadata size");
    }

    final String actualChecksum = sha256Hex(content);
    if (entry.checksum() == null || !entry.checksum().equalsIgnoreCase(actualChecksum)) {
      throw new FsContentConflictException(
          "uploaded content checksum does not match metadata checksum");
    }

    if (distributionService != null) {
      distributionService.distributeUpload(normalizedUsername, entry, content);
    } else {
      fsFileContentPort.save(normalizedUsername, normalizedEntryId, content);
      // Lazy visibility: en distribution.enabled=false, el flip lo hace el path legacy
      // local-blob aquí (vs el path de distribución que lo hace dentro de
      // distributeUploadStreaming). Sin esto, el entry queda contentUploaded=false y los
      // listings + sync delta filtran el archivo aunque la subida haya completado.
      fsEntryPort.save(entry.withContentUploaded(true));
    }
    return new FileContentUploadResult(normalizedEntryId, content.length, actualChecksum);
  }

  /**
   * Reads {@code contentLength} bytes from {@code input} and delegates to the orchestrator's
   * per-block streaming pipeline. The SHA-256 of the streamed bytes is validated against {@link
   * FsEntry#checksum()} inside the orchestrator (we cannot pre-validate without buffering the full
   * file). Falls back to {@link #upload(String, String, byte[])} when the orchestrator is absent
   * buffers the stream into RAM so use only in dev/minimal setups.
   *
   * @param username username owner
   * @param entryId entry id
   * @param input request input stream
   * @param contentLength total declared length (must match the actual bytes available)
   * @return upload result
   */
  public FileContentUploadResult uploadStreaming(
      final String username,
      final String entryId,
      final java.io.InputStream input,
      final long contentLength) {
    final String normalizedUsername = normalizeNonBlank(username, "username");
    final String normalizedEntryId = normalizeNonBlank(entryId, "entryId");
    if (input == null) {
      throw new IllegalArgumentException("input must not be null");
    }
    if (contentLength <= 0) {
      throw new IllegalArgumentException("contentLength must be greater than zero");
    }

    final FsEntry entry = requireActiveFileEntry(normalizedUsername, normalizedEntryId);
    if (entry.sizeBytes() != contentLength) {
      throw new FsContentConflictException("uploaded content size does not match metadata size");
    }

    if (distributionService != null) {
      distributionService.distributeUploadStreaming(
          normalizedUsername, entry, input, contentLength);
      // Checksum mismatch inside distribution → orchestrator throws FsContentConflictException.
      // Streaming path doesn't recompute SHA-256 here.
      return new FileContentUploadResult(normalizedEntryId, contentLength, entry.checksum());
    }

    // Legacy fallback: read the full stream into memory and call the byte[] path. Only used when
    // distribution is disabled (dev/minimal setups). For large files in this mode the JVM OOMs
    // the streaming path is the production answer.
    try {
      final byte[] buffered = input.readAllBytes();
      if (buffered.length != contentLength) {
        throw new FsContentConflictException(
            "stream byte count does not match declared contentLength");
      }
      return upload(normalizedUsername, normalizedEntryId, buffered);
    } catch (java.io.IOException ex) {
      throw new IllegalStateException("I/O error reading upload stream", ex);
    }
  }

  /**
   * Streaming download. Writes the reconstructed file directly to {@code output}. RAM peak per call
   * is bounded by {@code blockSize × n} regardless of file size.
   *
   * @param username username owner
   * @param entryId entry id
   * @param output destination stream (HTTP response body typically)
   * @return checksum of the served file (sourced from the manifest, not recomputed)
   */
  public String downloadStreaming(
      final String username, final String entryId, final java.io.OutputStream output) {
    final String normalizedUsername = normalizeNonBlank(username, "username");
    final String normalizedEntryId = normalizeNonBlank(entryId, "entryId");
    if (output == null) {
      throw new IllegalArgumentException("output must not be null");
    }

    final FsEntry entry = requireActiveFileEntry(normalizedUsername, normalizedEntryId);

    if (distributionService != null && entry.fileId() != null && !entry.fileId().isBlank()) {
      try {
        distributionService.reconstructDownloadStreaming(entry.fileId(), output);
        return entry.checksum();
      } catch (RuntimeException distributionEx) {
        // BYTES_FROM_TUTOR: cuando los peers ya no tienen los fragments tras
        // RETURN_TO_TUTOR, NodeFsRestoreService pulló los bytes del tutor y los persistió en
        // fsFileContentPort durante el restore. Fallback al blob local cierra el ciclo.
        LOGGER
            .atWarn()
            .setMessage(
                "Distribution reconstruct failed; attempting local blob fallback (BYTES_FROM_TUTOR"
                    + " path)")
            .addKeyValue("event", "DOWNLOAD_FALLBACK_LOCAL_BLOB")
            .addKeyValue("fileId", entry.fileId())
            .addKeyValue("error", distributionEx.getMessage())
            .log();
        // Fall through to local blob path below.
      }
    }

    // Local blob path (legacy + BYTES_FROM_TUTOR fallback).
    final byte[] content =
        fsFileContentPort
            .find(normalizedUsername, normalizedEntryId)
            .orElseThrow(() -> new NoSuchElementException("file content not found"));
    final String actualChecksum = sha256Hex(content);
    if (entry.checksum() == null || !entry.checksum().equalsIgnoreCase(actualChecksum)) {
      throw new FsContentConflictException(
          "stored content checksum does not match metadata checksum");
    }
    try {
      output.write(content);
    } catch (java.io.IOException ex) {
      throw new IllegalStateException("I/O error writing download to output", ex);
    }
    return actualChecksum;
  }

  /**
   * Loads content for a user entry.
   *
   * @param username username owner
   * @param entryId entry id
   * @return download payload
   */
  public FileContentDownloadResult download(final String username, final String entryId) {
    final String normalizedUsername = normalizeNonBlank(username, "username");
    final String normalizedEntryId = normalizeNonBlank(entryId, "entryId");
    final FsEntry entry = requireActiveFileEntry(normalizedUsername, normalizedEntryId);

    byte[] content = null;
    if (distributionService != null && entry.fileId() != null && !entry.fileId().isBlank()) {
      try {
        content = distributionService.reconstructDownload(entry.fileId());
      } catch (RuntimeException distributionEx) {
        // BYTES_FROM_TUTOR fallback (espejo del path streaming arriba).
        LOGGER
            .atWarn()
            .setMessage(
                "Distribution reconstruct failed; attempting local blob fallback (BYTES_FROM_TUTOR"
                    + " path)")
            .addKeyValue("event", "DOWNLOAD_FALLBACK_LOCAL_BLOB")
            .addKeyValue("fileId", entry.fileId())
            .addKeyValue("error", distributionEx.getMessage())
            .log();
      }
    }
    if (content == null) {
      content =
          fsFileContentPort
              .find(normalizedUsername, normalizedEntryId)
              .orElseThrow(() -> new NoSuchElementException("file content not found"));
    }

    final String actualChecksum = sha256Hex(content);
    if (entry.checksum() == null || !entry.checksum().equalsIgnoreCase(actualChecksum)) {
      throw new FsContentConflictException(
          "stored content checksum does not match metadata checksum");
    }

    return new FileContentDownloadResult(normalizedEntryId, content, actualChecksum);
  }

  /**
   * Deletes stored content for an entry. In distributed mode this is a no-op (the actual quota and
   * fragment cleanup happens via {@link FileSystemService#delete} which calls the orchestrator's
   * {@code releaseQuotaForFile}); in legacy local-blob mode it removes the local blob.
   *
   * @param username username owner
   * @param entryId entry id
   */
  public void deleteContent(final String username, final String entryId) {
    final String normalizedUsername = normalizeNonBlank(username, "username");
    final String normalizedEntryId = normalizeNonBlank(entryId, "entryId");
    if (distributionService == null) {
      fsFileContentPort.delete(normalizedUsername, normalizedEntryId);
    }
  }

  /**
   * Looks up an active FILE entry, throwing the same exceptions used by the upload/download flows.
   * Useful for HTTP controllers that need {@code sizeBytes}/{@code checksum} in response headers
   * before streaming the body.
   */
  public FsEntry requireActiveFile(final String username, final String entryId) {
    final String normalizedUsername = normalizeNonBlank(username, "username");
    final String normalizedEntryId = normalizeNonBlank(entryId, "entryId");
    return requireActiveFileEntry(normalizedUsername, normalizedEntryId);
  }

  private FsEntry requireActiveFileEntry(final String username, final String entryId) {
    final FsEntry entry =
        fsEntryPort
            .findByUsernameAndEntryId(username, entryId)
            .orElseThrow(() -> new NoSuchElementException("entry not found"));
    if (entry.entryType() != FsEntryType.FILE) {
      throw new IllegalArgumentException("entry is not a file");
    }
    if (entry.deleted()) {
      throw new NoSuchElementException("entry is deleted");
    }
    return entry;
  }

  private String normalizeNonBlank(final String value, final String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value.trim();
  }

  private String sha256Hex(final byte[] value) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 algorithm is not available", exception);
    }
  }
}
