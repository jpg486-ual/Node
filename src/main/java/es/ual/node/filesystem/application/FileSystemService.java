package es.ual.node.filesystem.application;

import es.ual.node.filesystem.domain.FsEntry;
import es.ual.node.filesystem.domain.FsEntryType;
import es.ual.node.filesystem.ports.out.FsEntryPort;
import es.ual.node.filesystem.ports.out.RemoteFileManifestStorePort;
import es.ual.node.recovery.ports.out.CustodiedFileManifestPort;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Application service for user-scoped filesystem metadata operations. */
public class FileSystemService {

  private static final Logger LOGGER = LoggerFactory.getLogger(FileSystemService.class);

  private final FsEntryPort fsEntryPort;
  private final Clock clock;
  private final CustodiedFileManifestPort manifestPort;
  private final FileContentDistributionService distributionService;
  // Remote DELETE call to the user's tutor when a file is deleted at origin.
  // Nullable for backward compat with tests / minimal wiring.
  private final RemoteFileManifestStorePort remoteFileManifestStorePort;
  private final String tutorBaseUrl;

  /** Convenience constructor, no distribution, no tutor replication. */
  public FileSystemService(final FsEntryPort fsEntryPort, final Clock clock) {
    this(fsEntryPort, clock, null, null, null, null);
  }

  /** Convenience constructor, no distribution, no tutor replication. */
  public FileSystemService(
      final FsEntryPort fsEntryPort,
      final Clock clock,
      final CustodiedFileManifestPort manifestPort) {
    this(fsEntryPort, clock, manifestPort, null, null, null);
  }

  /** Convenience constructor, no tutor replication. */
  public FileSystemService(
      final FsEntryPort fsEntryPort,
      final Clock clock,
      final CustodiedFileManifestPort manifestPort,
      final FileContentDistributionService distributionService) {
    this(fsEntryPort, clock, manifestPort, distributionService, null, null);
  }

  /**
   * Creates filesystem service with the full wiring.
   *
   * @param fsEntryPort filesystem persistence
   * @param clock clock
   * @param manifestPort optional manifest port for hard-delete cleanup. When non-null, {@link
   *     #delete} purges the local {@code recovery_file_manifest} row associated with the entry's
   *     {@code fileId}.
   * @param distributionService optional Reed-Solomon orchestrator. When non-null, {@link #delete}
   *     releases the user's quota and removes the client manifest + placements at origin.
   * @param remoteFileManifestStorePort optional outbound port. When non-null AND {@code
   *     tutorBaseUrl} is non-blank, {@link #delete} also fires a signed {@code DELETE
   *     /recovery/file-manifests/&#123;fileId&#125;} against the tutor. Failures are logged but do
   *     not abort the local delete.
   * @param tutorBaseUrl tutor base URL of this origin node, e.g. {@code http://node2:8080}
   */
  public FileSystemService(
      final FsEntryPort fsEntryPort,
      final Clock clock,
      final CustodiedFileManifestPort manifestPort,
      final FileContentDistributionService distributionService,
      final RemoteFileManifestStorePort remoteFileManifestStorePort,
      final String tutorBaseUrl) {
    if (fsEntryPort == null || clock == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    this.fsEntryPort = fsEntryPort;
    this.clock = clock;
    this.manifestPort = manifestPort;
    this.distributionService = distributionService;
    this.remoteFileManifestStorePort = remoteFileManifestStorePort;
    this.tutorBaseUrl = tutorBaseUrl == null ? null : tutorBaseUrl.trim();
  }

  /**
   * Upserts a filesystem entry and bumps path version.
   *
   * @param request upsert request
   * @return persisted entry
   */
  public FsEntry upsert(final FsUpsertRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }

    final String username = normalizeNonBlank(request.username(), "username");
    final String requestEntryId = normalizeOptional(request.entryId());
    final String path = normalizePath(request.path());
    final FsEntryType entryType = request.entryType();
    if (entryType == null) {
      throw new IllegalArgumentException("entryType must not be null");
    }

    final boolean deleted = request.deleted() != null && request.deleted();
    final long sizeBytes =
        entryType == FsEntryType.DIRECTORY
            ? 0L
            : (request.sizeBytes() == null ? 0L : request.sizeBytes());
    if (sizeBytes < 0) {
      throw new IllegalArgumentException("sizeBytes must be zero or greater");
    }

    final String checksum = entryType == FsEntryType.FILE ? request.checksum() : null;
    if (entryType == FsEntryType.FILE && !deleted && (checksum == null || checksum.isBlank())) {
      throw new IllegalArgumentException("checksum is required for active file entries");
    }

    final String requestFileId = entryType == FsEntryType.FILE ? request.fileId() : null;

    final Optional<FsEntry> pathEntry = fsEntryPort.findByUsernameAndPath(username, path);
    final Optional<FsEntry> previous;
    // Tombstones live at a mangled path
    // (`/__deleted__/<entryId>/<...>`) so a query for the
    // original path can only ever hit an alive row. The DB unique
    // constraint enforces the rest.
    if (pathEntry.isPresent()) {
      final FsEntry existing = pathEntry.get();
      if (requestEntryId == null) {
        throw new FsPathConflictException("path already exists; provide entryId to update");
      }
      if (!existing.entryId().equals(requestEntryId)) {
        throw new FsPathConflictException("path is already assigned to a different entry");
      }
      previous = Optional.of(existing);
    } else if (requestEntryId != null) {
      final Optional<FsEntry> entryById =
          fsEntryPort.findByUsernameAndEntryId(username, requestEntryId);
      if (entryById.isPresent() && !entryById.get().path().equals(path)) {
        throw new FsPathConflictException(
            "entryId belongs to a different path; use PATCH for move/rename");
      }
      previous = entryById;
    } else {
      previous = Optional.empty();
    }

    final String entryId = previous.map(FsEntry::entryId).orElse(UUID.randomUUID().toString());
    final long version = previous.map(FsEntry::version).orElse(0L) + 1L;

    // For active FILE entries, prefer the request value, then
    // the previous entry's fileId (preserves identity across re-uploads / version bumps),
    // and finally generate a new UUID. Directories and deleted entries get null.
    final String fileId;
    if (entryType == FsEntryType.FILE && !deleted) {
      if (requestFileId != null && !requestFileId.isBlank()) {
        fileId = requestFileId.trim();
      } else {
        fileId =
            previous
                .map(FsEntry::fileId)
                .filter(id -> id != null && !id.isBlank())
                .orElseGet(() -> UUID.randomUUID().toString());
      }
    } else {
      fileId = null;
    }

    // Para FILE entries el upsert siempre pone contentUploaded=false:
    // - Si es un upsert nuevo (POST /fs antes del PUT content), el entry queda oculto en /fs/tree
    //   hasta que el pipeline de distribución lo marque como uploaded al final.
    // - Si es un re-upsert (POST /fs sobre path existente, version+1), el contenido nuevo aún no
    //   se ha distribuido por tanto la versión nueva queda oculta hasta que el siguiente PUT
    // content
    //   complete. El cliente del subir ve la versión vieja en su listing hasta que la nueva
    //   termine; otros dispositivos también ven la vieja hasta que sustituye atómicamente.
    // Para DIRECTORY el constructor del record fuerza contentUploaded=true.
    // Para tombstones (deleted=true) mantengo contentUploaded=true ya que el sync los necesita
    // visibles para borrar la copia local del cliente.
    final boolean contentUploaded = (entryType != FsEntryType.FILE) || deleted;
    final FsEntry entry =
        new FsEntry(
            entryId,
            username,
            path,
            entryType,
            sizeBytes,
            checksum,
            fileId,
            version,
            clock.instant(),
            deleted,
            contentUploaded);
    fsEntryPort.save(entry);
    return entry;
  }

  /**
   * Returns user tree snapshot with optional incremental cursor.
   *
   * @param username username
   * @param sinceCursor cursor in epoch milliseconds; null for full snapshot
   * @return tree snapshot
   */
  public FsTreeSnapshot tree(final String username, final Long sinceCursor) {
    final String normalizedUsername = normalizeNonBlank(username, "username");

    final List<FsEntry> entries;
    if (sinceCursor == null) {
      entries = fsEntryPort.findByUsername(normalizedUsername);
    } else {
      if (sinceCursor < 0) {
        throw new IllegalArgumentException("sinceCursor must be zero or greater");
      }
      final Instant updatedAfter = Instant.ofEpochMilli(sinceCursor);
      entries = fsEntryPort.findByUsernameUpdatedAfter(normalizedUsername, updatedAfter);
    }

    long nextCursor = sinceCursor == null ? 0L : sinceCursor;
    for (FsEntry entry : entries) {
      nextCursor = Math.max(nextCursor, entry.updatedAt().toEpochMilli());
    }

    return new FsTreeSnapshot(normalizedUsername, clock.instant(), nextCursor, entries);
  }

  /**
   * Returns latest cursor for a user based on current metadata entries.
   *
   * @param username username
   * @return latest cursor in epoch milliseconds
   */
  public long currentCursor(final String username) {
    final String normalizedUsername = normalizeNonBlank(username, "username");
    long cursor = 0L;
    for (FsEntry entry : fsEntryPort.findByUsername(normalizedUsername)) {
      cursor = Math.max(cursor, entry.updatedAt().toEpochMilli());
    }
    return cursor;
  }

  /**
   * Renames or moves an existing filesystem entry.
   *
   * @param request patch request
   * @return updated entry
   */
  public FsEntry patch(final FsPatchRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }
    final String username = normalizeNonBlank(request.username(), "username");
    final String entryId = normalizeNonBlank(request.entryId(), "entryId");
    final String newPath = normalizePath(request.newPath());

    final FsEntry current =
        fsEntryPort
            .findByUsernameAndEntryId(username, entryId)
            .orElseThrow(() -> new NoSuchElementException("entry not found"));

    final Optional<FsEntry> existingPath = fsEntryPort.findByUsernameAndPath(username, newPath);
    if (existingPath.isPresent() && !existingPath.get().entryId().equals(current.entryId())) {
      throw new FsPathConflictException("target path is already in use");
    }

    // Replicate the new path to the tutor BEFORE persisting locally.
    // Fail-closed: if the tutor is unreachable the local rename aborts so the strong invariant
    // (tutor's directoryPath reflects current FS state) holds. Skipped for DIRECTORY entries and
    // FILE entries without a fileId (no tutor manifest exists for them).
    if (remoteFileManifestStorePort != null
        && tutorBaseUrl != null
        && !tutorBaseUrl.isBlank()
        && current.entryType() == FsEntryType.FILE
        && current.fileId() != null
        && !current.fileId().isBlank()) {
      // El directoryPath del tutor lleva username como primer segmento.
      // El regex del tutor (`TutorFileManifestCustodyService.DIRECTORY_PATH_PATTERN`) exige
      // que NO termine con `/` salvo cuando el path es exactamente `/`. Cuando el archivo se
      // mueve a la raíz, `extractParentPathForManifest` devuelve `"/"` y concatenar daría
      // `"/<user>/"` (trailing slash → 400). Mismo patrón root-aware que aplica
      // `FileContentDistributionService.distributeUploadStreaming` en el upload inicial.
      final String parentPath = extractParentPathForManifest(newPath);
      final String newDirectoryPath = "/" + username + ("/".equals(parentPath) ? "" : parentPath);
      final String newFileName = extractFileNameForManifest(newPath, current.entryId());
      try {
        remoteFileManifestStorePort.updatePath(
            current.fileId(), newDirectoryPath, newFileName, tutorBaseUrl);
      } catch (RuntimeException ex) {
        LOGGER
            .atWarn()
            .setMessage(
                "Manifest path replication to tutor FAILED — local rename aborted; operator "
                    + "action required if pattern persists")
            .addKeyValue("event", "TUTOR_MANIFEST_PATH_REPLICATION_FAILED")
            .addKeyValue("severity", "high")
            .addKeyValue("fileId", current.fileId())
            .addKeyValue("entryId", current.entryId())
            .addKeyValue("oldPath", current.path())
            .addKeyValue("newPath", newPath)
            .addKeyValue("tutorBaseUrl", tutorBaseUrl)
            .addKeyValue("error", ex.getMessage())
            .log();
        throw new TutorManifestReplicationException(
            "Manifest path replication to tutor at " + tutorBaseUrl + " failed; rename aborted",
            ex);
      }
    }

    final FsEntry updated =
        new FsEntry(
            current.entryId(),
            username,
            newPath,
            current.entryType(),
            current.sizeBytes(),
            current.checksum(),
            current.fileId(),
            current.version() + 1L,
            clock.instant(),
            current.deleted());
    fsEntryPort.save(updated);
    return updated;
  }

  /**
   * Duplicado intencional de {@code FileContentDistributionService.extractParentPath}. Misma
   * semántica: {@code /A/file.txt → /A}, {@code /file.txt → /}, blank/null → /. Si emerge un tercer
   * caller, mover a una utilidad compartida bajo {@code filesystem/domain/}.
   */
  private static String extractParentPathForManifest(final String path) {
    if (path == null || path.isBlank()) {
      return "/";
    }
    final int slash = path.lastIndexOf('/');
    if (slash <= 0) {
      return "/";
    }
    return path.substring(0, slash);
  }

  /**
   * Duplicado intencional de {@code FileContentDistributionService.extractFileName}. Si el path no
   * tiene basename utilizable, cae al {@code fallback} (típicamente el {@code entryId}).
   */
  private static String extractFileNameForManifest(final String path, final String fallback) {
    if (path == null) {
      return fallback;
    }
    final int slash = path.lastIndexOf('/');
    if (slash < 0 || slash == path.length() - 1) {
      return fallback;
    }
    return path.substring(slash + 1);
  }

  /**
   * Renames or moves a sub-tree. Atomically rewrites the path of every active entry rooted at
   * {@code fromPath} to be rooted at {@code toPath} and replicates the metadata change for every
   * affected FILE manifest to the tutor in a SINGLE bulk roundtrip, instead of N.
   *
   * <p>Order of operations (fail-closed verdadero):
   *
   * <ol>
   *   <li>Validate input paths and ensure {@code toPath} is not a descendant of {@code fromPath}.
   *   <li>List the sub-tree (root + descendants, leaves first).
   *   <li>Compute every {@code newPath}; reject if any conflicts with an existing out-of-subtree
   *       entry.
   *   <li>Build the bulk update for FILE entries with non-null {@code fileId} (DIRECTORY entries
   *       are local-only, no manifest custodied at the tutor).
   *   <li>Call the tutor ONCE with the bulk update. If it fails, log a high-severity WARN and throw
   *       {@link TutorManifestReplicationException}; nothing local is persisted.
   *   <li>Persist the local rewrites in leaf-to-root order.
   * </ol>
   *
   * @param username username
   * @param fromPath sub-tree root (must exist; FILE or DIRECTORY)
   * @param toPath new sub-tree root (must not exist as an unrelated entry; must not be a descendant
   *     of {@code fromPath})
   * @return rewritten entries, in leaf-first order
   * @throws NoSuchElementException when {@code fromPath} does not exist
   * @throws FsPathConflictException when any computed {@code newPath} clashes with an existing
   *     out-of-subtree entry, or when {@code toPath} is a descendant of {@code fromPath}
   * @throws TutorManifestReplicationException when the tutor refuses the bulk update; local state
   *     remains untouched (rollback verdadero)
   */
  public List<FsEntry> moveSubtree(
      final String username, final String fromPath, final String toPath) {
    final String normalizedUsername = normalizeNonBlank(username, "username");
    final String normalizedFromPath = normalizePath(fromPath);
    final String normalizedToPath = normalizePath(toPath);
    if (normalizedFromPath.equals(normalizedToPath)) {
      throw new IllegalArgumentException("fromPath and toPath must be different");
    }
    if (normalizedToPath.equals(normalizedFromPath + "/")
        || normalizedToPath.startsWith(normalizedFromPath + "/")) {
      throw new FsPathConflictException("toPath must not be a descendant of fromPath");
    }

    final List<FsEntry> subtree =
        fsEntryPort.findByUsernameAndPathSubtree(normalizedUsername, normalizedFromPath);
    if (subtree.isEmpty()) {
      throw new NoSuchElementException("subtree root not found: " + normalizedFromPath);
    }

    // Compute newPath for every entry; reject when any newPath collides with an out-of-subtree
    // entry. Same-subtree collisions are impossible because we re-key only the prefix.
    final java.util.Set<String> subtreeEntryIds = new java.util.HashSet<>();
    for (FsEntry e : subtree) {
      subtreeEntryIds.add(e.entryId());
    }
    final java.util.LinkedHashMap<String, String> newPathByEntryId =
        new java.util.LinkedHashMap<>();
    for (FsEntry e : subtree) {
      final String suffix = e.path().substring(normalizedFromPath.length());
      final String newPath = normalizedToPath + suffix;
      newPathByEntryId.put(e.entryId(), newPath);
      final java.util.Optional<FsEntry> existing =
          fsEntryPort.findByUsernameAndPath(normalizedUsername, newPath);
      if (existing.isPresent() && !subtreeEntryIds.contains(existing.get().entryId())) {
        throw new FsPathConflictException(
            "target path is already in use by another entry: " + newPath);
      }
    }

    // Build the bulk tutor update: only FILE entries with non-null fileId. DIRECTORY entries do
    // not have a custodied manifest.
    final java.util.List<RemoteFileManifestStorePort.BulkUpdateEntry> bulkEntries =
        new java.util.ArrayList<>();
    for (FsEntry e : subtree) {
      if (e.entryType() != FsEntryType.FILE || e.fileId() == null || e.fileId().isBlank()) {
        continue;
      }
      final String newPath = newPathByEntryId.get(e.entryId());
      // Mismo patrón root-aware que la rama single-entry: para mover a la raíz, el parent
      // es `"/"` y NO debe concatenarse para evitar trailing slash en `directoryPath`.
      final String parentPath = extractParentPathForManifest(newPath);
      final String newDirectoryPath =
          "/" + normalizedUsername + ("/".equals(parentPath) ? "" : parentPath);
      final String newFileName = extractFileNameForManifest(newPath, e.entryId());
      bulkEntries.add(
          new RemoteFileManifestStorePort.BulkUpdateEntry(
              e.fileId(), newDirectoryPath, newFileName));
    }

    if (!bulkEntries.isEmpty()
        && remoteFileManifestStorePort != null
        && tutorBaseUrl != null
        && !tutorBaseUrl.isBlank()) {
      try {
        remoteFileManifestStorePort.updatePathBulk(bulkEntries, tutorBaseUrl);
      } catch (RuntimeException ex) {
        LOGGER
            .atWarn()
            .setMessage(
                "Bulk manifest path replication to tutor FAILED — local subtree move aborted; "
                    + "operator action required if pattern persists")
            .addKeyValue("event", "TUTOR_MANIFEST_BULK_REPLICATION_FAILED")
            .addKeyValue("severity", "high")
            .addKeyValue("subtreeRoot", normalizedFromPath)
            .addKeyValue("targetRoot", normalizedToPath)
            .addKeyValue("affectedFileCount", bulkEntries.size())
            .addKeyValue("tutorBaseUrl", tutorBaseUrl)
            .addKeyValue("error", ex.getMessage())
            .log();
        throw new TutorManifestReplicationException(
            "Bulk manifest path replication to tutor at "
                + tutorBaseUrl
                + " failed; subtree move aborted",
            ex);
      }
    }

    // Persist locally in leaf-first order (subtree is already sorted by adapter).
    final Instant now = clock.instant();
    final java.util.List<FsEntry> updated = new java.util.ArrayList<>();
    for (FsEntry e : subtree) {
      final String newPath = newPathByEntryId.get(e.entryId());
      final FsEntry rewritten =
          new FsEntry(
              e.entryId(),
              normalizedUsername,
              newPath,
              e.entryType(),
              e.sizeBytes(),
              e.checksum(),
              e.fileId(),
              e.version() + 1L,
              now,
              e.deleted());
      fsEntryPort.save(rewritten);
      updated.add(rewritten);
    }

    LOGGER
        .atInfo()
        .setMessage("Sub-tree moved")
        .addKeyValue("event", "BULK_MOVE_SUBTREE_COMPLETED")
        .addKeyValue("username", normalizedUsername)
        .addKeyValue("fromPath", normalizedFromPath)
        .addKeyValue("toPath", normalizedToPath)
        .addKeyValue("affectedCount", updated.size())
        .addKeyValue("tutorReplicatedFiles", bulkEntries.size())
        .log();
    return updated;
  }

  /**
   * Marks existing entry as deleted.
   *
   * @param username username
   * @param entryId entry identifier
   * @return deleted entry version
   */
  public FsEntry delete(final String username, final String entryId) {
    final String normalizedUsername = normalizeNonBlank(username, "username");
    final String normalizedEntryId = normalizeNonBlank(entryId, "entryId");

    final FsEntry current =
        fsEntryPort
            .findByUsernameAndEntryId(normalizedUsername, normalizedEntryId)
            .orElseThrow(() -> new NoSuchElementException("entry not found"));

    // La fila se conserva como tombstone para que el sync incremental
    // propague la eliminación a otros dispositivos del usuario, pero su
    // metadata "pesada" se vacía: sizeBytes=0, checksum=null, fileId=null.
    // El manifest de recovery se hard-deletea aquí mismo (no sirve para nada
    // si el archivo dejó de existir). Los fragmentos de los custodios se
    // liberan más tarde via custody-liveness sin necesidad de
    // intervención explícita aquí.
    //
    // El path se modifica" a `/__deleted__/<entryId>/<originalPath>`
    // para que el unique constraint `(username, path)` pueda mantenerse sin
    // bloquear futuras subidas con el mismo nombre. La constraint actúa de
    // safety net contra race conditions entre uploads simultáneos al
    // mismo path. Audit trail intacto: el filename original queda al final
    // del path mangleado, recuperable visualmente desde DB queries.
    final String previousFileId = current.fileId();
    final String mangledPath = manglePathForTombstone(current.entryId(), current.path());

    final FsEntry deleted =
        new FsEntry(
            current.entryId(),
            current.username(),
            mangledPath,
            current.entryType(),
            0L,
            null,
            null,
            current.version() + 1L,
            clock.instant(),
            true);
    fsEntryPort.save(deleted);

    if (manifestPort != null && previousFileId != null && !previousFileId.isBlank()) {
      manifestPort.deleteByFileId(previousFileId);
    }

    // Cuando el archivo borrado pertenece al pipeline RS distribuido,
    // libera la cuota del usuario y purga el manifest + placements del lado
    // origen. Idempotente, si no hay manifest (FsEntry sin distribución
    // previa o ya purgado), el orchestrator hace no-op silencioso.
    if (distributionService != null && previousFileId != null && !previousFileId.isBlank()) {
      distributionService.releaseQuotaForFile(normalizedUsername, previousFileId);
    }

    // Signed DELETE to the tutor's recovery_file_manifest row.
    // Failure does NOT abort the local delete, the renewal worker cross-check will
    // skip the missed manifest naturally. This avoids cascading the local user-facing delete on
    // transient tutor outages.
    if (remoteFileManifestStorePort != null
        && tutorBaseUrl != null
        && !tutorBaseUrl.isBlank()
        && previousFileId != null
        && !previousFileId.isBlank()) {
      try {
        remoteFileManifestStorePort.delete(previousFileId, tutorBaseUrl);
      } catch (RuntimeException ex) {
        LOGGER
            .atWarn()
            .setMessage("Tutor manifest delete failed; relying on TTL natural expiry")
            .addKeyValue("event", "TUTOR_MANIFEST_DELETE_FAILED")
            .addKeyValue("fileId", previousFileId)
            .addKeyValue("tutorBaseUrl", tutorBaseUrl)
            .addKeyValue("reason", ex.getMessage())
            .log();
      }
    }

    return deleted;
  }

  private String normalizeNonBlank(final String value, final String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value.trim();
  }

  private String normalizePath(final String path) {
    final String normalized = normalizeNonBlank(path, "path");
    if (!normalized.startsWith("/")) {
      throw new IllegalArgumentException("path must start with '/'");
    }
    // El namespace `/__deleted__/...` está reservado para
    // tombstones generados por `delete()`. Rechazar input usuario que lo
    // empiece evita spoofing, un usuario malicioso no puede crear
    // entradas en esa zona ni renombrar archivos para colocarlos ahí.
    if (normalized.startsWith(TOMBSTONE_PATH_PREFIX)) {
      throw new IllegalArgumentException(
          "path must not start with reserved tombstone prefix '" + TOMBSTONE_PATH_PREFIX + "'");
    }
    return normalized;
  }

  /**
   * Convierte un path "vivo" en su forma de tombstone, garantizando que el resultado es único entre
   * filas borradas (gracias al UUID en el medio) y disjoint del namespace de paths reales (gracias
   * al prefijo {@value #TOMBSTONE_PATH_PREFIX}). El nombre de archivo original queda al final del
   * path para que un debug visual de la DB siga siendo informativo.
   *
   * @param entryId UUID del entry (single source of truth para unicidad).
   * @param originalPath path vivo previo a la mutación.
   * @return path mangleado en formato {@code /__deleted__/<entryId>/<originalPathStripped>}.
   */
  /**
   * Bulk variant of {@link #delete}. Marks every active entry of the sub-tree rooted at {@code
   * path} as a tombstone (deleted=true, fileId=null, path mangled, version+1), purges the
   * corresponding {@code client_file_manifest} rows, releases user quota and replicates the
   * affected fileIds removal to the tutor in a SINGLE bulk roundtrip.
   *
   * <p>Order of operations (espejo del delete single):
   *
   * <ol>
   *   <li>List the sub-tree (leaves first via {@code findByUsernameAndPathSubtree}).
   *   <li>For every FILE entry with a non-null fileId: purge local manifest + release quota.
   *   <li>Persist the tombstone for every entry (leaf-first).
   *   <li>Bulk DELETE to the tutor with all affected fileIds. If it fails, log a high-severity WARN
   *       — the local tombstones persist; the renewal worker cross-check is the compensation.
   * </ol>
   *
   * @param username username
   * @param path sub-tree root
   * @return tombstones in leaf-first order
   * @throws NoSuchElementException when {@code path} does not exist
   */
  public List<FsEntry> deleteSubtree(final String username, final String path) {
    final String normalizedUsername = normalizeNonBlank(username, "username");
    final String normalizedPath = normalizePath(path);

    final List<FsEntry> subtree =
        fsEntryPort.findByUsernameAndPathSubtree(normalizedUsername, normalizedPath);
    if (subtree.isEmpty()) {
      throw new NoSuchElementException("subtree root not found: " + normalizedPath);
    }

    // Local first: purge manifests + release quota + persist tombstones (leaf-first).
    final List<String> fileIdsToDelete = new java.util.ArrayList<>();
    final List<FsEntry> tombstones = new java.util.ArrayList<>();
    final Instant now = clock.instant();
    for (FsEntry e : subtree) {
      final String previousFileId = e.fileId();
      if (e.entryType() == FsEntryType.FILE
          && previousFileId != null
          && !previousFileId.isBlank()) {
        if (manifestPort != null) {
          manifestPort.deleteByFileId(previousFileId);
        }
        if (distributionService != null) {
          distributionService.releaseQuotaForFile(normalizedUsername, previousFileId);
        }
        fileIdsToDelete.add(previousFileId);
      }
      final FsEntry tombstone =
          new FsEntry(
              e.entryId(),
              e.username(),
              manglePathForTombstone(e.entryId(), e.path()),
              e.entryType(),
              0L,
              null,
              null,
              e.version() + 1L,
              now,
              true);
      fsEntryPort.save(tombstone);
      tombstones.add(tombstone);
    }

    // Bulk DELETE to the tutor, espejo del delete single.
    boolean tutorBulkAttempted = false;
    if (!fileIdsToDelete.isEmpty()
        && remoteFileManifestStorePort != null
        && tutorBaseUrl != null
        && !tutorBaseUrl.isBlank()) {
      tutorBulkAttempted = true;
      try {
        remoteFileManifestStorePort.deleteBulk(fileIdsToDelete, tutorBaseUrl);
      } catch (RuntimeException ex) {
        LOGGER
            .atWarn()
            .setMessage(
                "Bulk manifest delete to tutor FAILED — local tombstones persist; renewal "
                    + "cross-check + tutor TTL natural expiry will compensate")
            .addKeyValue("event", "TUTOR_MANIFEST_BULK_DELETE_FAILED")
            .addKeyValue("severity", "high")
            .addKeyValue("subtreeRoot", normalizedPath)
            .addKeyValue("affectedFileCount", fileIdsToDelete.size())
            .addKeyValue("tutorBaseUrl", tutorBaseUrl)
            .addKeyValue("error", ex.getMessage())
            .log();
      }
    }

    LOGGER
        .atInfo()
        .setMessage("Sub-tree deleted")
        .addKeyValue("event", "BULK_DELETE_SUBTREE_COMPLETED")
        .addKeyValue("username", normalizedUsername)
        .addKeyValue("subtreeRoot", normalizedPath)
        .addKeyValue("affectedCount", tombstones.size())
        .addKeyValue("tutorBulkAttempted", tutorBulkAttempted)
        .addKeyValue("tutorAffectedFileCount", fileIdsToDelete.size())
        .log();
    return tombstones;
  }

  private static String manglePathForTombstone(final String entryId, final String originalPath) {
    final String stripped = originalPath.startsWith("/") ? originalPath.substring(1) : originalPath;
    return TOMBSTONE_PATH_PREFIX + entryId + "/" + stripped;
  }

  private static final String TOMBSTONE_PATH_PREFIX = "/__deleted__/";

  private String normalizeOptional(final String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }
}
