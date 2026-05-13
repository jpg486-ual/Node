package es.ual.node.filesystem.adapters.out.memory;

import es.ual.node.filesystem.ports.out.FileManifestPort;
import es.ual.node.negotiation.domain.FileManifest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory adapter for {@link FileManifestPort}. */
public class InMemoryFileManifestPort implements FileManifestPort {

  private final Map<String, FileManifest> manifestsByFileId = new ConcurrentHashMap<>();

  @Override
  public void save(final FileManifest manifest, final String username, final String entryId) {
    if (manifest == null) {
      throw new IllegalArgumentException("manifest must not be null");
    }
    if (username == null || username.isBlank()) {
      throw new IllegalArgumentException("username must not be blank");
    }
    if (entryId == null || entryId.isBlank()) {
      throw new IllegalArgumentException("entryId must not be blank");
    }
    manifestsByFileId.put(manifest.fileId(), manifest);
  }

  @Override
  public Optional<FileManifest> findByFileId(final String fileId) {
    if (fileId == null || fileId.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(manifestsByFileId.get(fileId.trim()));
  }

  @Override
  public void deleteByFileId(final String fileId) {
    if (fileId == null || fileId.isBlank()) {
      return;
    }
    manifestsByFileId.remove(fileId.trim());
  }

  @Override
  public List<String> findAllFileIds() {
    return List.copyOf(manifestsByFileId.keySet());
  }

  /** Returns the current count of stored manifests (test helper, not on the port). */
  public int size() {
    return manifestsByFileId.size();
  }
}
