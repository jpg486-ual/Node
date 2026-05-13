package es.ual.node.filesystem.adapters.out.memory;

import es.ual.node.filesystem.domain.FileUploadSession;
import es.ual.node.filesystem.ports.out.FileUploadSessionPort;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory upload session adapter. */
public class InMemoryFileUploadSessionPort implements FileUploadSessionPort {

  private final Map<String, FileUploadSession> sessionsByKey = new ConcurrentHashMap<>();

  @Override
  public void save(final FileUploadSession session) {
    sessionsByKey.put(key(session.username(), session.sessionId()), session);
  }

  @Override
  public Optional<FileUploadSession> findByUsernameAndSessionId(
      final String username, final String sessionId) {
    return Optional.ofNullable(sessionsByKey.get(key(username, sessionId)));
  }

  private String key(final String username, final String sessionId) {
    return username + "::" + sessionId;
  }
}
