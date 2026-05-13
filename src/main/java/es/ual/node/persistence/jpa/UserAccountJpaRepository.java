package es.ual.node.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** JPA repository for user accounts. */
public interface UserAccountJpaRepository extends JpaRepository<UserAccountJpaEntity, String> {

  /**
   * Atomic conditional update reserving bytes against the user's quota. Increments {@code
   * quota_used_bytes} only when the new total stays within {@code quota_mb * 1048576}.
   *
   * <p>{@code u.quotaMb} se castea explícitamente a {@code long} para que PostgreSQL evalúe la
   * comparación en BIGINT. Sin el cast, {@code quota_mb (INT) * 1048576 (INT)} hace overflow cuando
   * {@code quota_mb >= 2048}.
   *
   * @param username target username
   * @param bytes bytes to reserve
   * @param bytesPerMb fixed conversion factor (1048576) supplied by the caller to avoid relying on
   *     dialect-specific arithmetic in the WHERE clause
   * @return number of rows updated (1 = reserved, 0 = exceeded)
   */
  @Modifying
  @Query(
      value =
          "UPDATE UserAccountJpaEntity u "
              + "SET u.quotaUsedBytes = u.quotaUsedBytes + :bytes "
              + "WHERE u.username = :username "
              + "AND u.quotaUsedBytes + :bytes <= CAST(u.quotaMb AS long) * :bytesPerMb")
  int tryReserveBytes(
      @Param("username") String username,
      @Param("bytes") long bytes,
      @Param("bytesPerMb") long bytesPerMb);

  /**
   * Releases previously reserved bytes (clamps to zero on under-flow).
   *
   * @param username target username
   * @param bytes bytes to release
   * @return number of rows updated
   */
  @Modifying
  @Query(
      value =
          "UPDATE UserAccountJpaEntity u "
              + "SET u.quotaUsedBytes = CASE WHEN u.quotaUsedBytes - :bytes < 0 THEN 0 "
              + "ELSE u.quotaUsedBytes - :bytes END "
              + "WHERE u.username = :username")
  int releaseBytes(@Param("username") String username, @Param("bytes") long bytes);
}
