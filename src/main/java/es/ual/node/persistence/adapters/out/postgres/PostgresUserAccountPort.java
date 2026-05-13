package es.ual.node.persistence.adapters.out.postgres;

import es.ual.node.persistence.jpa.UserAccountJpaEntity;
import es.ual.node.persistence.jpa.UserAccountJpaRepository;
import es.ual.node.userregistration.domain.UserAccount;
import es.ual.node.userregistration.domain.UserRole;
import es.ual.node.userregistration.ports.out.UserAccountPort;
import java.util.Locale;
import java.util.Optional;

/** PostgreSQL adapter for user accounts. */
public class PostgresUserAccountPort implements UserAccountPort {

  private final UserAccountJpaRepository repository;

  /**
   * Creates adapter.
   *
   * @param repository JPA repository
   */
  public PostgresUserAccountPort(final UserAccountJpaRepository repository) {
    if (repository == null) {
      throw new IllegalArgumentException("repository must not be null");
    }
    this.repository = repository;
  }

  @Override
  public boolean existsByUsername(final String username) {
    return repository.existsById(normalize(username));
  }

  @Override
  public void save(final UserAccount userAccount) {
    final UserAccountJpaEntity entity = new UserAccountJpaEntity();
    entity.setUsername(normalize(userAccount.username()));
    entity.setPasswordHash(userAccount.passwordHash());
    entity.setQuotaMb(userAccount.quotaMb());
    entity.setRole(userAccount.role().name());
    entity.setCreatedAt(userAccount.createdAt());
    repository.save(entity);
  }

  @Override
  public Optional<UserAccount> findByUsername(final String username) {
    return repository.findById(normalize(username)).map(this::toDomain);
  }

  private UserAccount toDomain(final UserAccountJpaEntity entity) {
    return new UserAccount(
        entity.getUsername(),
        entity.getPasswordHash(),
        entity.getQuotaMb(),
        entity.getRole() == null ? UserRole.END_USER : UserRole.parse(entity.getRole()),
        entity.getCreatedAt());
  }

  private String normalize(final String username) {
    if (username == null || username.isBlank()) {
      throw new IllegalArgumentException("username must not be blank");
    }
    return username.trim().toLowerCase(Locale.ROOT);
  }
}
