package es.ual.node.filesystem.domain;

import java.time.Instant;

/**
 * Immutable filesystem metadata entry.
 *
 * <p>{@code fileId} carries the {@link es.ual.node.negotiation.domain.FileManifest#fileId} of the
 * manifest that backs this entry. <strong>Mandatory for {@link FsEntryType#FILE}</strong> entries
 * MUST be {@code null} for {@link FsEntryType#DIRECTORY} entries since directories have no
 * fragmented file manifest.
 *
 * <p>{@code contentUploaded} (lazy visibility): {@code FALSE} mientras {@code POST /fs} ha creado
 * el entry pero la distribución del contenido todavía no ha terminado. {@code TRUE} cuando el
 * pipeline persiste el manifest + placements atómicamente al final. Listings filtran por {@code
 * contentUploaded=TRUE} para que otros dispositivos del mismo usuario no vean entries FILE
 * pendientes. DIRECTORY entries fuerzan {@code true} (no tienen contenido distribuible).
 */
public record FsEntry(
    String entryId,
    String username,
    String path,
    FsEntryType entryType,
    long sizeBytes,
    String checksum,
    String fileId,
    long version,
    Instant updatedAt,
    boolean deleted,
    boolean contentUploaded) {

  /** Creates validated filesystem entry. */
  public FsEntry {
    if (entryId == null || entryId.isBlank()) {
      throw new IllegalArgumentException("entryId must not be blank");
    }
    if (username == null || username.isBlank()) {
      throw new IllegalArgumentException("username must not be blank");
    }
    if (path == null || path.isBlank() || !path.startsWith("/")) {
      throw new IllegalArgumentException("path must start with '/'");
    }
    if (entryType == null) {
      throw new IllegalArgumentException("entryType must not be null");
    }
    if (sizeBytes < 0) {
      throw new IllegalArgumentException("sizeBytes must be zero or greater");
    }
    if (version <= 0) {
      throw new IllegalArgumentException("version must be greater than zero");
    }
    if (updatedAt == null) {
      throw new IllegalArgumentException("updatedAt must not be null");
    }

    if (entryType == FsEntryType.FILE) {
      if (!deleted && (checksum == null || checksum.isBlank())) {
        throw new IllegalArgumentException("checksum must not be blank for active file entries");
      }
      checksum = checksum == null ? null : checksum.trim();
      if (!deleted && (fileId == null || fileId.isBlank())) {
        throw new IllegalArgumentException("fileId must not be blank for active file entries");
      }
      fileId = fileId == null ? null : fileId.trim();
    } else {
      checksum = null;
      sizeBytes = 0;
      if (fileId != null && !fileId.isBlank()) {
        throw new IllegalArgumentException("fileId must be null for directory entries");
      }
      fileId = null;
      // DIRECTORY entries no tienen contenido distribuible — siempre visibles.
      contentUploaded = true;
    }

    entryId = entryId.trim();
    username = username.trim();
    path = path.trim();
  }

  /**
   * Legacy 10-arg constructor: defaults {@code contentUploaded=true}, modo compat para
   * tests/factories que no quieren razonar sobre visibilidad lazy. Producción usa el 11-arg
   * explícito.
   */
  public FsEntry(
      final String entryId,
      final String username,
      final String path,
      final FsEntryType entryType,
      final long sizeBytes,
      final String checksum,
      final String fileId,
      final long version,
      final Instant updatedAt,
      final boolean deleted) {
    this(
        entryId, username, path, entryType, sizeBytes, checksum, fileId, version, updatedAt,
        deleted, true);
  }

  /** Returns a copy of this entry with {@code contentUploaded} flipped to the given value. */
  public FsEntry withContentUploaded(final boolean newContentUploaded) {
    return new FsEntry(
        entryId,
        username,
        path,
        entryType,
        sizeBytes,
        checksum,
        fileId,
        version,
        updatedAt,
        deleted,
        newContentUploaded);
  }

  /**
   * Returns a copy of this entry with {@code fileId} replaced. Used by the upload distribution flow
   * when a fresh fileId is generated on every {@code PUT /content} so the entry's persisted fileId
   * stays in sync with the manifest+placements just written.
   */
  public FsEntry withFileId(final String newFileId) {
    return new FsEntry(
        entryId,
        username,
        path,
        entryType,
        sizeBytes,
        checksum,
        newFileId,
        version,
        updatedAt,
        deleted,
        contentUploaded);
  }
}
