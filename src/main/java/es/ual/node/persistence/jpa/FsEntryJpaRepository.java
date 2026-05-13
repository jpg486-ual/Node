package es.ual.node.persistence.jpa;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** JPA repository for filesystem entries. */
public interface FsEntryJpaRepository extends JpaRepository<FsEntryJpaEntity, String> {

  /**
   * Finds user entries ordered by update instant.
   *
   * @param username username
   * @return entries
   */
  List<FsEntryJpaEntity> findByUsernameOrderByUpdatedAtAsc(String username);

  /**
   * Finds changed entries ordered by update instant.
   *
   * @param username username
   * @param updatedAt lower bound
   * @return entries
   */
  List<FsEntryJpaEntity> findByUsernameAndUpdatedAtAfterOrderByUpdatedAtAsc(
      String username, Instant updatedAt);

  /**
   * Finds latest entry by username and path.
   *
   * @param username username
   * @param path path
   * @return optional entry
   */
  Optional<FsEntryJpaEntity> findByUsernameAndPath(String username, String path);

  /**
   * Finds entry by owner and entry id.
   *
   * @param username username
   * @param entryId entry id
   * @return optional entry
   */
  Optional<FsEntryJpaEntity> findByUsernameAndEntryId(String username, String entryId);

  /**
   * Finds entry by manifest file id.
   *
   * @param fileId file id
   * @return optional entry
   */
  Optional<FsEntryJpaEntity> findByFileId(String fileId);

  /**
   * Lists active entries forming the sub-tree rooted at {@code subtreeRoot}. Includes the root and
   * every active descendant whose path starts with {@code subtreeRoot + "/"}. Excludes deleted
   * (tombstone) entries. Ordered by path length descending so leaves come first. Useful for
   * path-uniqueness checks during batch updates.
   *
   * @param username username
   * @param subtreeRoot sub-tree root absolute path (no trailing slash)
   * @param childPrefix {@code subtreeRoot + "/"} (precomputed by the adapter to keep the LIKE
   *     pattern deterministic; SQL CONCAT inside the query would also work but explicit is safer)
   * @return entries longest path first; empty list when the root does not exist
   */
  @Query(
      "SELECT e FROM FsEntryJpaEntity e "
          + "WHERE e.username = :username "
          + "AND e.deleted = false "
          + "AND (e.path = :subtreeRoot OR e.path LIKE :childPrefix) "
          + "ORDER BY LENGTH(e.path) DESC, e.path ASC")
  List<FsEntryJpaEntity> findActiveSubtree(
      @Param("username") String username,
      @Param("subtreeRoot") String subtreeRoot,
      @Param("childPrefix") String childPrefix);
}
