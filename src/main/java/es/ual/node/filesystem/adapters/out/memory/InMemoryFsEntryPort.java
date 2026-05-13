package es.ual.node.filesystem.adapters.out.memory;

import es.ual.node.filesystem.domain.FsEntry;
import es.ual.node.filesystem.domain.FsEntryType;
import es.ual.node.filesystem.ports.out.FsEntryPort;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory filesystem metadata adapter. */
public class InMemoryFsEntryPort implements FsEntryPort {

  private final Map<String, Map<String, FsEntry>> entriesByUser = new ConcurrentHashMap<>();

  @Override
  public void save(final FsEntry fsEntry) {
    if (fsEntry == null) {
      throw new IllegalArgumentException("fsEntry must not be null");
    }
    final String username = normalize(fsEntry.username());
    final Map<String, FsEntry> userEntries =
        entriesByUser.computeIfAbsent(username, key -> new ConcurrentHashMap<>());
    // El adapter Postgres usa entry_id como PRIMARY KEY, así
    // que un save() que cambia el path (por ejemplo el mangle de delete:
    // /x.txt → /__deleted__/<id>/x.txt) actualiza la fila in-place sin
    // dejar rastro en la ubicación previa. El adapter in-memory
    // necesita emular ese comportamiento explícitamente: localizar y
    // eliminar cualquier entrada anterior con el mismo entry_id pero un
    // path distinto antes de insertar.
    userEntries
        .entrySet()
        .removeIf(
            existing ->
                existing.getValue().entryId().equals(fsEntry.entryId())
                    && !existing.getKey().equals(fsEntry.path()));
    userEntries.put(fsEntry.path(), fsEntry);
  }

  @Override
  public Optional<FsEntry> findByUsernameAndPath(final String username, final String path) {
    final Map<String, FsEntry> userEntries = entriesByUser.get(normalize(username));
    if (userEntries == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(userEntries.get(path));
  }

  @Override
  public Optional<FsEntry> findByUsernameAndEntryId(final String username, final String entryId) {
    if (entryId == null || entryId.isBlank()) {
      throw new IllegalArgumentException("entryId must not be blank");
    }
    final Map<String, FsEntry> userEntries = entriesByUser.get(normalize(username));
    if (userEntries == null) {
      return Optional.empty();
    }
    return userEntries.values().stream()
        .filter(entry -> entry.entryId().equals(entryId.trim()))
        .findFirst();
  }

  @Override
  public List<FsEntry> findByUsername(final String username) {
    final Map<String, FsEntry> userEntries = entriesByUser.get(normalize(username));
    if (userEntries == null) {
      return List.of();
    }
    return userEntries.values().stream()
        .filter(InMemoryFsEntryPort::isVisibleInListing)
        .sorted(Comparator.comparing(FsEntry::updatedAt).thenComparing(FsEntry::path))
        .toList();
  }

  @Override
  public List<FsEntry> findByUsernameUpdatedAfter(
      final String username, final Instant updatedAfter) {
    if (updatedAfter == null) {
      throw new IllegalArgumentException("updatedAfter must not be null");
    }
    return findByUsername(username).stream()
        .filter(entry -> entry.updatedAt().isAfter(updatedAfter))
        .toList();
  }

  /**
   * Visibilidad lazy. Mismas reglas que en {@code PostgresFsEntryPort}: tombstones pasan (sync los
   * necesita), DIRECTORY pasa (sin contenido distribuible), FILE solo si {@code
   * contentUploaded=true}.
   */
  private static boolean isVisibleInListing(final FsEntry entry) {
    if (entry.deleted()) {
      return true;
    }
    if (entry.entryType() == FsEntryType.DIRECTORY) {
      return true;
    }
    return entry.contentUploaded();
  }

  @Override
  public Optional<FsEntry> findByFileId(final String fileId) {
    if (fileId == null || fileId.isBlank()) {
      return Optional.empty();
    }
    final String trimmed = fileId.trim();
    return entriesByUser.values().stream()
        .flatMap(map -> map.values().stream())
        .filter(entry -> trimmed.equals(entry.fileId()))
        .findFirst();
  }

  @Override
  public List<FsEntry> findByUsernameAndPathSubtree(
      final String username, final String subtreeRoot) {
    if (subtreeRoot == null || subtreeRoot.isBlank()) {
      return List.of();
    }
    final String key = normalize(username);
    final Map<String, FsEntry> userEntries = entriesByUser.get(key);
    if (userEntries == null) {
      return List.of();
    }
    final String root = subtreeRoot.trim();
    final String prefix = root + "/";
    return userEntries.values().stream()
        .filter(e -> !e.deleted())
        .filter(e -> e.path().equals(root) || e.path().startsWith(prefix))
        .sorted((a, b) -> Integer.compare(b.path().length(), a.path().length()))
        .toList();
  }

  private String normalize(final String username) {
    if (username == null || username.isBlank()) {
      throw new IllegalArgumentException("username must not be blank");
    }
    return username.trim().toLowerCase(Locale.ROOT);
  }
}
