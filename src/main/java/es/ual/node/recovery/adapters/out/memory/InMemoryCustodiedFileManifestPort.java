package es.ual.node.recovery.adapters.out.memory;

import es.ual.node.recovery.domain.CustodiedFileManifest;
import es.ual.node.recovery.ports.out.CustodiedFileManifestPort;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory adapter for {@link CustodiedFileManifestPort} (default for memory profile). */
public class InMemoryCustodiedFileManifestPort implements CustodiedFileManifestPort {

  private final Map<String, CustodiedFileManifest> store = new ConcurrentHashMap<>();

  @Override
  public void save(final CustodiedFileManifest manifest) {
    if (manifest == null) {
      throw new IllegalArgumentException("manifest must not be null");
    }
    store.put(manifest.fileId(), manifest);
  }

  @Override
  public Optional<CustodiedFileManifest> findByFileId(final String fileId) {
    if (fileId == null || fileId.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(store.get(fileId));
  }

  @Override
  public List<CustodiedFileManifest> findByRequesterNodeId(final String requesterNodeId) {
    if (requesterNodeId == null || requesterNodeId.isBlank()) {
      return List.of();
    }
    return store.values().stream()
        .filter(m -> requesterNodeId.equals(m.requesterNodeId()))
        .sorted(Comparator.comparing(CustodiedFileManifest::storedAt).reversed())
        .toList();
  }

  @Override
  public boolean deleteByFileId(final String fileId) {
    if (fileId == null || fileId.isBlank()) {
      return false;
    }
    return store.remove(fileId) != null;
  }

  @Override
  public int deleteByFileIds(final Iterable<String> fileIds) {
    if (fileIds == null) {
      return 0;
    }
    final Set<String> ids = new HashSet<>();
    fileIds.forEach(
        id -> {
          if (id != null && !id.isBlank()) {
            ids.add(id.trim());
          }
        });
    int deleted = 0;
    for (final String id : ids) {
      if (store.remove(id) != null) {
        deleted++;
      }
    }
    return deleted;
  }

  @Override
  public int markSupervisedCheckOk(final String requesterNodeId, final Instant at) {
    if (requesterNodeId == null || requesterNodeId.isBlank() || at == null) {
      return 0;
    }
    int updated = 0;
    for (final Map.Entry<String, CustodiedFileManifest> entry : store.entrySet()) {
      final CustodiedFileManifest m = entry.getValue();
      if (requesterNodeId.equals(m.requesterNodeId())) {
        store.put(entry.getKey(), m.withSupervisedCheckOk(at));
        updated++;
      }
    }
    return updated;
  }

  @Override
  public int markSupervisedCheckFailed(final String requesterNodeId, final Instant at) {
    if (requesterNodeId == null || requesterNodeId.isBlank() || at == null) {
      return 0;
    }
    int updated = 0;
    for (final Map.Entry<String, CustodiedFileManifest> entry : store.entrySet()) {
      final CustodiedFileManifest m = entry.getValue();
      if (requesterNodeId.equals(m.requesterNodeId())) {
        store.put(entry.getKey(), m.withSupervisedCheckFailed(at));
        updated++;
      }
    }
    return updated;
  }

  @Override
  public List<String> listSupervisedNodeIds() {
    return store.values().stream()
        .map(CustodiedFileManifest::requesterNodeId)
        .distinct()
        .sorted()
        .toList();
  }
}
