package es.ual.node.persistence.adapters.out.postgres;

import es.ual.node.filesystem.domain.FsEntry;
import es.ual.node.filesystem.domain.FsEntryType;
import es.ual.node.filesystem.ports.out.FsEntryPort;
import es.ual.node.persistence.jpa.FsEntryJpaEntity;
import es.ual.node.persistence.jpa.FsEntryJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** PostgreSQL adapter for filesystem metadata. */
public class PostgresFsEntryPort implements FsEntryPort {

  private final FsEntryJpaRepository repository;

  /**
   * Creates adapter.
   *
   * @param repository JPA repository
   */
  public PostgresFsEntryPort(final FsEntryJpaRepository repository) {
    if (repository == null) {
      throw new IllegalArgumentException("repository must not be null");
    }
    this.repository = repository;
  }

  @Override
  public void save(final FsEntry fsEntry) {
    repository.save(toEntity(fsEntry));
  }

  @Override
  public Optional<FsEntry> findByUsernameAndPath(final String username, final String path) {
    return repository
        .findByUsernameAndPath(normalizeUsername(username), normalizePath(path))
        .map(this::toDomain);
  }

  @Override
  public Optional<FsEntry> findByUsernameAndEntryId(final String username, final String entryId) {
    if (entryId == null || entryId.isBlank()) {
      throw new IllegalArgumentException("entryId must not be blank");
    }
    return repository
        .findByUsernameAndEntryId(normalizeUsername(username), entryId.trim())
        .map(this::toDomain);
  }

  @Override
  public List<FsEntry> findByUsername(final String username) {
    return repository.findByUsernameOrderByUpdatedAtAsc(normalizeUsername(username)).stream()
        .map(this::toDomain)
        .filter(this::isVisibleInListing)
        .toList();
  }

  @Override
  public List<FsEntry> findByUsernameUpdatedAfter(
      final String username, final Instant updatedAfter) {
    if (updatedAfter == null) {
      throw new IllegalArgumentException("updatedAfter must not be null");
    }
    return repository
        .findByUsernameAndUpdatedAtAfterOrderByUpdatedAtAsc(
            normalizeUsername(username), updatedAfter)
        .stream()
        .map(this::toDomain)
        .filter(this::isVisibleInListing)
        .toList();
  }

  @Override
  public Optional<FsEntry> findByFileId(final String fileId) {
    if (fileId == null || fileId.isBlank()) {
      return Optional.empty();
    }
    return repository.findByFileId(fileId.trim()).map(this::toDomain);
  }

  @Override
  public List<FsEntry> findByUsernameAndPathSubtree(
      final String username, final String subtreeRoot) {
    if (subtreeRoot == null || subtreeRoot.isBlank()) {
      return List.of();
    }
    final String root = normalizePath(subtreeRoot);
    final String childPrefix = root + "/%";
    return repository.findActiveSubtree(normalizeUsername(username), root, childPrefix).stream()
        .map(this::toDomain)
        .toList();
  }

  /**
   * Entries pendientes (FILE no terminadas de subir) se excluyen del listing; tombstones
   * (deleted=true) se incluyen siempre porque sync los necesita para borrar la copia local del
   * cliente; DIRECTORY pasa siempre.
   */
  private boolean isVisibleInListing(final FsEntry entry) {
    if (entry.deleted()) {
      return true;
    }
    if (entry.entryType() == FsEntryType.DIRECTORY) {
      return true;
    }
    return entry.contentUploaded();
  }

  private FsEntryJpaEntity toEntity(final FsEntry entry) {
    final FsEntryJpaEntity entity = new FsEntryJpaEntity();
    entity.setEntryId(entry.entryId());
    entity.setUsername(normalizeUsername(entry.username()));
    entity.setPath(normalizePath(entry.path()));
    entity.setEntryType(entry.entryType().name());
    entity.setSizeBytes(entry.sizeBytes());
    entity.setChecksum(entry.checksum());
    entity.setFileId(entry.fileId());
    entity.setVersion(entry.version());
    entity.setUpdatedAt(entry.updatedAt());
    entity.setDeleted(entry.deleted());
    entity.setContentUploaded(entry.contentUploaded());
    return entity;
  }

  private FsEntry toDomain(final FsEntryJpaEntity entity) {
    return new FsEntry(
        entity.getEntryId(),
        entity.getUsername(),
        entity.getPath(),
        FsEntryType.valueOf(entity.getEntryType()),
        entity.getSizeBytes(),
        entity.getChecksum(),
        entity.getFileId(),
        entity.getVersion(),
        entity.getUpdatedAt(),
        entity.isDeleted(),
        entity.isContentUploaded());
  }

  private String normalizeUsername(final String username) {
    if (username == null || username.isBlank()) {
      throw new IllegalArgumentException("username must not be blank");
    }
    return username.trim().toLowerCase(Locale.ROOT);
  }

  private String normalizePath(final String path) {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("path must not be blank");
    }
    final String normalized = path.trim();
    if (!normalized.startsWith("/")) {
      throw new IllegalArgumentException("path must start with '/'");
    }
    return normalized;
  }
}
