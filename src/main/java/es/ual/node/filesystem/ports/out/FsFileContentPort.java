package es.ual.node.filesystem.ports.out;

import java.util.Optional;

/** Filesystem file-content persistence boundary. */
public interface FsFileContentPort {

  /**
   * Saves binary content for an entry.
   *
   * @param username username owner
   * @param entryId entry id
   * @param content binary content
   */
  void save(String username, String entryId, byte[] content);

  /**
   * Reads binary content for an entry.
   *
   * @param username username owner
   * @param entryId entry id
   * @return content when present
   */
  Optional<byte[]> find(String username, String entryId);

  /**
   * Deletes binary content for an entry if present.
   *
   * @param username username owner
   * @param entryId entry id
   */
  void delete(String username, String entryId);
}
