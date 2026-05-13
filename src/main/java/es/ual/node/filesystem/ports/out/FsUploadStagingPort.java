package es.ual.node.filesystem.ports.out;

import java.io.InputStream;
import java.util.Optional;

/** Temporary staging storage for resumable upload chunks. */
public interface FsUploadStagingPort {

  /**
   * Resets staging stream for session.
   *
   * @param username username
   * @param sessionId session id
   */
  void reset(String username, String sessionId);

  /**
   * Appends chunk at expected offset.
   *
   * @param username username
   * @param sessionId session id
   * @param offset expected offset
   * @param chunk bytes to append
   */
  void append(String username, String sessionId, long offset, byte[] chunk);

  /**
   * Reads staged payload.
   *
   * @param username username
   * @param sessionId session id
   * @return bytes when present
   */
  Optional<byte[]> readAll(String username, String sessionId);

  /**
   * Opens a streaming view of the staged payload. Used by {@code
   * FileUploadSessionService.complete()} to delegate to the per-block RS distribution pipeline
   * without buffering the full file in RAM. Caller MUST close the returned stream.
   *
   * @param username username
   * @param sessionId session id
   * @return open input stream when staged content is present, empty otherwise
   */
  Optional<InputStream> openInputStream(String username, String sessionId);

  /**
   * Deletes staged payload.
   *
   * @param username username
   * @param sessionId session id
   */
  void delete(String username, String sessionId);
}
