package es.ual.node.userregistration.adapters.out.memory;

import es.ual.node.userregistration.domain.UserAccount;
import es.ual.node.userregistration.ports.out.UserAccountPort;
import es.ual.node.userregistration.ports.out.UserQuotaPort;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory adapter for {@link UserQuotaPort}. Tracks reserved bytes in a concurrent map and
 * delegates the user lookup to {@link UserAccountPort} to resolve the configured {@code quotaMb}
 * allowance. Atomicity for the conditional update in {@link #tryReserve(String, long)} is achieved
 * by synchronizing on the per-username slot.
 */
public class InMemoryUserQuotaPort implements UserQuotaPort {

  private static final long BYTES_PER_MB = 1024L * 1024L;

  private final UserAccountPort userAccountPort;
  private final Map<String, Long> usedByUsername = new ConcurrentHashMap<>();

  /** Creates adapter. */
  public InMemoryUserQuotaPort(final UserAccountPort userAccountPort) {
    if (userAccountPort == null) {
      throw new IllegalArgumentException("userAccountPort must not be null");
    }
    this.userAccountPort = userAccountPort;
  }

  @Override
  public synchronized boolean tryReserve(final String username, final long bytes) {
    final String normalized = normalize(username);
    if (bytes < 0) {
      throw new IllegalArgumentException("bytes must not be negative");
    }

    final UserAccount account =
        userAccountPort
            .findByUsername(normalized)
            .orElseThrow(() -> new IllegalArgumentException("user not found: " + normalized));
    final long quotaTotalBytes = account.quotaMb() * BYTES_PER_MB;

    final long current = usedByUsername.getOrDefault(normalized, 0L);
    if (current + bytes > quotaTotalBytes) {
      return false;
    }
    usedByUsername.put(normalized, current + bytes);
    return true;
  }

  @Override
  public synchronized void release(final String username, final long bytes) {
    final String normalized = normalize(username);
    if (bytes < 0) {
      throw new IllegalArgumentException("bytes must not be negative");
    }

    final long current = usedByUsername.getOrDefault(normalized, 0L);
    usedByUsername.put(normalized, Math.max(0L, current - bytes));
  }

  @Override
  public long usedBytes(final String username) {
    return usedByUsername.getOrDefault(normalize(username), 0L);
  }

  private static String normalize(final String username) {
    if (username == null || username.isBlank()) {
      throw new IllegalArgumentException("username must not be blank");
    }
    return username.trim();
  }
}
