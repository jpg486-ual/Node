package es.ual.node.userregistration.ports.out;

/**
 * User storage quota persistence boundary.
 *
 * <p>Tracks the bytes a user has currently reserved against their {@code quotaMb} allowance,
 * including the Reed-Solomon distribution overhead. Operations are atomic at the persistence layer
 * so concurrent uploads from the same user cannot over-commit.
 */
public interface UserQuotaPort {

  /**
   * Atomically reserves the requested bytes against the user's quota.
   *
   * @param username username
   * @param bytes bytes to reserve (already including any RS overhead the caller wants to charge)
   * @return {@code true} on success, {@code false} when the requested amount would exceed the
   *     remaining quota
   * @throws IllegalArgumentException when {@code username} is blank or {@code bytes < 0}
   */
  boolean tryReserve(String username, long bytes);

  /**
   * Releases previously reserved bytes (idempotent on under-flow: never decreases below zero).
   *
   * @param username username
   * @param bytes bytes to release
   * @throws IllegalArgumentException when {@code username} is blank or {@code bytes < 0}
   */
  void release(String username, long bytes);

  /**
   * Reads currently reserved bytes for the user. Used by {@code GET /auth/me} and observability.
   *
   * @param username username
   * @return currently reserved bytes, or 0 when the user does not exist
   * @throws IllegalArgumentException when {@code username} is blank
   */
  long usedBytes(String username);
}
