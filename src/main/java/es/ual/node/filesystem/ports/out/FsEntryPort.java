package es.ual.node.filesystem.ports.out;

import es.ual.node.filesystem.domain.FsEntry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Filesystem metadata persistence boundary. */
public interface FsEntryPort {

  /**
   * Saves current entry state.
   *
   * @param fsEntry entry
   */
  void save(FsEntry fsEntry);

  /**
   * Finds latest entry by username and path.
   *
   * @param username username
   * @param path path
   * @return optional entry
   */
  Optional<FsEntry> findByUsernameAndPath(String username, String path);

  /**
   * Finds latest entry by username and entry identifier.
   *
   * @param username username
   * @param entryId entry id
   * @return optional entry
   */
  Optional<FsEntry> findByUsernameAndEntryId(String username, String entryId);

  /**
   * Returns all current entries for user.
   *
   * @param username username
   * @return entries sorted by update time
   */
  List<FsEntry> findByUsername(String username);

  /**
   * Returns entries changed after instant.
   *
   * @param username username
   * @param updatedAfter lower bound (exclusive)
   * @return changed entries sorted by update time
   */
  List<FsEntry> findByUsernameUpdatedAfter(String username, Instant updatedAfter);

  /**
   * Finds the entry whose {@code fileId} matches the given identifier. Used by the node restore
   * flow to decide whether a custodied manifest already maps to an existing FS entry.
   *
   * @param fileId file id from a {@link es.ual.node.negotiation.domain.FileManifest}
   * @return optional entry
   */
  Optional<FsEntry> findByFileId(String fileId);

  /**
   * Returns the active entries that form the sub-tree rooted at {@code subtreeRoot}. Includes the
   * entry whose {@code path == subtreeRoot} plus every active descendant whose {@code path} starts
   * with {@code subtreeRoot + "/"}. Excludes deleted (tombstone) entries.
   *
   * <p>Used by {@code FileSystemService.moveSubtree(...)} to enumerate everything that will move in
   * a single bulk operation. Order: leaves before ancestors (longest path first) so the caller can
   * apply path-uniqueness checks and writes without intermediate conflicts.
   *
   * @param username username
   * @param subtreeRoot absolute path (no trailing slash); must be either a DIRECTORY or a FILE
   * @return list of active entries in the sub-tree, longest path first; empty when the root does
   *     not exist
   */
  List<FsEntry> findByUsernameAndPathSubtree(String username, String subtreeRoot);
}
