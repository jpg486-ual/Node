package es.ual.node.filesystem.adapters.out.memory;

import es.ual.node.filesystem.ports.out.FsFileContentPort;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory file content adapter. */
public class InMemoryFsFileContentPort implements FsFileContentPort {

  private final Map<String, byte[]> contentByKey = new ConcurrentHashMap<>();

  @Override
  public void save(final String username, final String entryId, final byte[] content) {
    contentByKey.put(key(username, entryId), content.clone());
  }

  @Override
  public Optional<byte[]> find(final String username, final String entryId) {
    final byte[] content = contentByKey.get(key(username, entryId));
    return content == null ? Optional.empty() : Optional.of(content.clone());
  }

  @Override
  public void delete(final String username, final String entryId) {
    contentByKey.remove(key(username, entryId));
  }

  private String key(final String username, final String entryId) {
    return username + "::" + entryId;
  }
}
