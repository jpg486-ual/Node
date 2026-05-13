package es.ual.node.filesystem.ports.out;

import es.ual.node.filesystem.domain.FileUploadSession;
import java.util.Optional;

/** Resumable upload session persistence boundary. */
public interface FileUploadSessionPort {

  /**
   * Saves session state.
   *
   * @param session session
   */
  void save(FileUploadSession session);

  /**
   * Finds session by id and user.
   *
   * @param username username
   * @param sessionId session id
   * @return session when present
   */
  Optional<FileUploadSession> findByUsernameAndSessionId(String username, String sessionId);
}
