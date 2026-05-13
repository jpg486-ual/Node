package es.ual.node.persistence.adapters.out.postgres;

import es.ual.node.persistence.jpa.UserSessionJpaEntity;
import es.ual.node.persistence.jpa.UserSessionJpaRepository;
import es.ual.node.userregistration.domain.UserSession;
import es.ual.node.userregistration.ports.out.UserSessionPort;
import java.util.Optional;

/** PostgreSQL adapter for user sessions. */
public class PostgresUserSessionPort implements UserSessionPort {

  private final UserSessionJpaRepository repository;

  /**
   * Creates adapter.
   *
   * @param repository JPA repository
   */
  public PostgresUserSessionPort(final UserSessionJpaRepository repository) {
    if (repository == null) {
      throw new IllegalArgumentException("repository must not be null");
    }
    this.repository = repository;
  }

  @Override
  public void save(final UserSession userSession) {
    final UserSessionJpaEntity entity = new UserSessionJpaEntity();
    entity.setToken(userSession.token());
    entity.setUsername(userSession.username());
    entity.setIssuedAt(userSession.issuedAt());
    entity.setExpiresAt(userSession.expiresAt());
    entity.setRevoked(userSession.revoked());
    repository.save(entity);
  }

  @Override
  public Optional<UserSession> findByToken(final String token) {
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("token must not be blank");
    }
    return repository.findById(token.trim()).map(this::toDomain);
  }

  @Override
  public void revoke(final String token) {
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("token must not be blank");
    }
    final Optional<UserSessionJpaEntity> entity = repository.findById(token.trim());
    if (entity.isEmpty()) {
      return;
    }
    final UserSessionJpaEntity current = entity.get();
    current.setRevoked(true);
    repository.save(current);
  }

  private UserSession toDomain(final UserSessionJpaEntity entity) {
    return new UserSession(
        entity.getToken(),
        entity.getUsername(),
        entity.getIssuedAt(),
        entity.getExpiresAt(),
        entity.isRevoked());
  }
}
