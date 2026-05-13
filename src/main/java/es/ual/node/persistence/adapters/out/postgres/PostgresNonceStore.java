package es.ual.node.persistence.adapters.out.postgres;

import es.ual.node.identitysecurity.ports.out.NonceStore;
import es.ual.node.persistence.jpa.NonceJpaEntity;
import es.ual.node.persistence.jpa.NonceJpaRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL-backed nonce store. */
public class PostgresNonceStore implements NonceStore {

  private final NonceJpaRepository repository;
  private final Clock clock;

  /**
   * Creates nonce store.
   *
   * @param repository nonce repository
   * @param clock clock
   */
  public PostgresNonceStore(final NonceJpaRepository repository, final Clock clock) {
    if (repository == null || clock == null) {
      throw new IllegalArgumentException("Dependencies must not be null");
    }
    this.repository = repository;
    this.clock = clock;
  }

  /** {@inheritDoc} */
  @Override
  @Transactional
  public boolean markIfAbsent(final String nonce, final Instant expiresAt) {
    if (nonce == null || nonce.isBlank()) {
      throw new IllegalArgumentException("nonce must not be blank");
    }
    if (expiresAt == null) {
      throw new IllegalArgumentException("expiresAt must not be null");
    }

    repository.deleteExpired(clock.instant());

    final String normalized = nonce.trim();
    if (repository.existsById(normalized)) {
      return false;
    }

    final NonceJpaEntity entity = new NonceJpaEntity();
    entity.setNonce(normalized);
    entity.setExpiresAt(expiresAt);
    repository.save(entity);
    return true;
  }
}
