package es.ual.node.persistence.adapters.out.postgres;

import es.ual.node.persistence.jpa.UserAccountJpaRepository;
import es.ual.node.userregistration.ports.out.UserQuotaPort;
import java.util.Locale;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL adapter for {@link UserQuotaPort}. */
public class PostgresUserQuotaPort implements UserQuotaPort {

  private static final long BYTES_PER_MB = 1024L * 1024L;

  private final UserAccountJpaRepository repository;

  /** Creates adapter. */
  public PostgresUserQuotaPort(final UserAccountJpaRepository repository) {
    if (repository == null) {
      throw new IllegalArgumentException("repository must not be null");
    }
    this.repository = repository;
  }

  @Override
  @Transactional
  public boolean tryReserve(final String username, final long bytes) {
    if (bytes < 0) {
      throw new IllegalArgumentException("bytes must not be negative");
    }
    final int updated = repository.tryReserveBytes(normalize(username), bytes, BYTES_PER_MB);
    return updated > 0;
  }

  @Override
  @Transactional
  public void release(final String username, final long bytes) {
    if (bytes < 0) {
      throw new IllegalArgumentException("bytes must not be negative");
    }
    repository.releaseBytes(normalize(username), bytes);
  }

  @Override
  public long usedBytes(final String username) {
    return repository
        .findById(normalize(username))
        .map(entity -> entity.getQuotaUsedBytes())
        .orElse(0L);
  }

  private static String normalize(final String username) {
    if (username == null || username.isBlank()) {
      throw new IllegalArgumentException("username must not be blank");
    }
    return username.trim().toLowerCase(Locale.ROOT);
  }
}
