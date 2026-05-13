package es.ual.node.filesystem.application;

import es.ual.node.filesystem.domain.FileUploadSession;
import es.ual.node.filesystem.domain.FileUploadSessionStatus;
import es.ual.node.filesystem.domain.FsEntry;
import es.ual.node.filesystem.domain.FsEntryType;
import es.ual.node.filesystem.ports.out.FileUploadSessionPort;
import es.ual.node.filesystem.ports.out.FsEntryPort;
import es.ual.node.filesystem.ports.out.FsFileContentPort;
import es.ual.node.filesystem.ports.out.FsUploadStagingPort;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.NoSuchElementException;
import java.util.UUID;

/** Application service for durable resumable uploads. */
public class FileUploadSessionService {

  private final FsEntryPort fsEntryPort;
  private final FsFileContentPort fsFileContentPort;
  private final FileUploadSessionPort fileUploadSessionPort;
  private final FsUploadStagingPort fsUploadStagingPort;
  private final Clock clock;
  private final FileContentDistributionService distributionService;

  /** Legacy constructor (no distribution wiring). kept for tests and minimal setups. */
  public FileUploadSessionService(
      final FsEntryPort fsEntryPort,
      final FsFileContentPort fsFileContentPort,
      final FileUploadSessionPort fileUploadSessionPort,
      final FsUploadStagingPort fsUploadStagingPort,
      final Clock clock) {
    this(fsEntryPort, fsFileContentPort, fileUploadSessionPort, fsUploadStagingPort, clock, null);
  }

  /**
   * Creates service. When {@code distributionService} is non-null the {@code complete()} flow
   * streams the staged file straight to the per-block RS distribution pipeline instead of promoting
   * it as a local blob.
   */
  public FileUploadSessionService(
      final FsEntryPort fsEntryPort,
      final FsFileContentPort fsFileContentPort,
      final FileUploadSessionPort fileUploadSessionPort,
      final FsUploadStagingPort fsUploadStagingPort,
      final Clock clock,
      final FileContentDistributionService distributionService) {
    if (fsEntryPort == null
        || fsFileContentPort == null
        || fileUploadSessionPort == null
        || fsUploadStagingPort == null
        || clock == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    this.fsEntryPort = fsEntryPort;
    this.fsFileContentPort = fsFileContentPort;
    this.fileUploadSessionPort = fileUploadSessionPort;
    this.fsUploadStagingPort = fsUploadStagingPort;
    this.clock = clock;
    this.distributionService = distributionService;
  }

  /** Creates new upload session for file entry. */
  public FileUploadSessionView create(final String username, final String entryId) {
    final String normalizedUsername = normalizeNonBlank(username, "username");
    final String normalizedEntryId = normalizeNonBlank(entryId, "entryId");
    final FsEntry entry = requireActiveFileEntry(normalizedUsername, normalizedEntryId);

    final Instant now = clock.instant();
    final FileUploadSession session =
        new FileUploadSession(
            UUID.randomUUID().toString(),
            normalizedUsername,
            normalizedEntryId,
            entry.sizeBytes(),
            entry.checksum(),
            0L,
            FileUploadSessionStatus.OPEN,
            now,
            now,
            null);
    fileUploadSessionPort.save(session);
    fsUploadStagingPort.reset(normalizedUsername, session.sessionId());
    return FileUploadSessionView.fromDomain(session);
  }

  /** Appends upload chunk at expected offset. */
  public FileUploadSessionView appendChunk(
      final String username, final String sessionId, final long offset, final byte[] chunk) {
    final String normalizedUsername = normalizeNonBlank(username, "username");
    final String normalizedSessionId = normalizeNonBlank(sessionId, "sessionId");
    if (offset < 0) {
      throw new IllegalArgumentException("offset must be zero or greater");
    }
    if (chunk == null) {
      throw new IllegalArgumentException("chunk must not be null");
    }

    final FileUploadSession current =
        fileUploadSessionPort
            .findByUsernameAndSessionId(normalizedUsername, normalizedSessionId)
            .orElseThrow(() -> new NoSuchElementException("upload session not found"));
    if (current.status() != FileUploadSessionStatus.OPEN) {
      throw new IllegalArgumentException("upload session is not open");
    }
    if (offset != current.uploadedBytes()) {
      throw new FsContentConflictException("chunk offset does not match current uploaded bytes");
    }

    final long nextUploaded = current.uploadedBytes() + chunk.length;
    if (nextUploaded > current.expectedSizeBytes()) {
      throw new FsContentConflictException("uploaded bytes exceed expected size");
    }

    fsUploadStagingPort.append(normalizedUsername, normalizedSessionId, offset, chunk);
    final FileUploadSession updated =
        new FileUploadSession(
            current.sessionId(),
            current.username(),
            current.entryId(),
            current.expectedSizeBytes(),
            current.expectedChecksum(),
            nextUploaded,
            current.status(),
            current.createdAt(),
            clock.instant(),
            current.completedAt());
    fileUploadSessionPort.save(updated);
    return FileUploadSessionView.fromDomain(updated);
  }

  /** Completes upload and promotes staged content to final content storage. */
  public FileContentUploadResult complete(final String username, final String sessionId) {
    final String normalizedUsername = normalizeNonBlank(username, "username");
    final String normalizedSessionId = normalizeNonBlank(sessionId, "sessionId");

    final FileUploadSession current =
        fileUploadSessionPort
            .findByUsernameAndSessionId(normalizedUsername, normalizedSessionId)
            .orElseThrow(() -> new NoSuchElementException("upload session not found"));
    if (current.status() != FileUploadSessionStatus.OPEN) {
      throw new IllegalArgumentException("upload session is not open");
    }
    if (current.uploadedBytes() != current.expectedSizeBytes()) {
      throw new FsContentConflictException("upload is incomplete");
    }

    final String resultChecksum;
    if (distributionService != null) {
      resultChecksum = promoteThroughDistribution(normalizedUsername, current);
    } else {
      resultChecksum = promoteAsLocalBlob(normalizedUsername, normalizedSessionId, current);
    }
    fsUploadStagingPort.delete(normalizedUsername, normalizedSessionId);

    final Instant now = clock.instant();
    final FileUploadSession completed =
        new FileUploadSession(
            current.sessionId(),
            current.username(),
            current.entryId(),
            current.expectedSizeBytes(),
            current.expectedChecksum(),
            current.uploadedBytes(),
            FileUploadSessionStatus.COMPLETED,
            current.createdAt(),
            now,
            now);
    fileUploadSessionPort.save(completed);
    return new FileContentUploadResult(
        current.entryId(), current.expectedSizeBytes(), resultChecksum);
  }

  /**
   * Streams the staged file straight to the per-block RS distribution pipeline. Validates SHA-256
   * inside the orchestrator (vs {@link FsEntry#checksum()}). No need to buffer the staged file in
   * RAM here. Returns the entry's expected checksum (already validated downstream).
   */
  private String promoteThroughDistribution(
      final String username, final FileUploadSession session) {
    final FsEntry entry = requireActiveFileEntry(username, session.entryId());
    try (java.io.InputStream input =
        fsUploadStagingPort
            .openInputStream(username, session.sessionId())
            .orElseThrow(() -> new NoSuchElementException("staged upload content not found"))) {
      distributionService.distributeUploadStreaming(
          username, entry, input, session.expectedSizeBytes());
    } catch (java.io.IOException ex) {
      throw new IllegalStateException("I/O error reading staged upload stream", ex);
    }
    return session.expectedChecksum();
  }

  /** Legacy local-blob promotion path (no distribution wiring). */
  private String promoteAsLocalBlob(
      final String username, final String sessionId, final FileUploadSession session) {
    final byte[] staged =
        fsUploadStagingPort
            .readAll(username, sessionId)
            .orElseThrow(() -> new NoSuchElementException("staged upload content not found"));
    if (staged.length != session.expectedSizeBytes()) {
      throw new FsContentConflictException("staged content size mismatch");
    }
    final String checksum = sha256Hex(staged);
    if (!checksum.equalsIgnoreCase(session.expectedChecksum())) {
      throw new FsContentConflictException("staged content checksum mismatch");
    }
    fsFileContentPort.save(username, session.entryId(), staged);
    // En distribution.enabled=false (modo legacy bootstrap), el flip de
    // contentUploaded=true ocurre aquí en el path local-blob. Sin esto el upload via session
    // resumable deja el entry oculto en /fs/tree y /sync/changes aunque haya completado.
    fsEntryPort
        .findByUsernameAndEntryId(username, session.entryId())
        .map(entry -> entry.withContentUploaded(true))
        .ifPresent(fsEntryPort::save);
    return checksum;
  }

  /** Returns current upload session state. */
  public FileUploadSessionView get(final String username, final String sessionId) {
    final String normalizedUsername = normalizeNonBlank(username, "username");
    final String normalizedSessionId = normalizeNonBlank(sessionId, "sessionId");
    final FileUploadSession session =
        fileUploadSessionPort
            .findByUsernameAndSessionId(normalizedUsername, normalizedSessionId)
            .orElseThrow(() -> new NoSuchElementException("upload session not found"));
    return FileUploadSessionView.fromDomain(session);
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
