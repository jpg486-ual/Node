package es.ual.node.persistence.adapters.out.postgres;

import es.ual.node.persistence.jpa.RegistrationCodeJpaEntity;
import es.ual.node.persistence.jpa.RegistrationCodeJpaRepository;
import es.ual.node.userregistration.domain.RegistrationCode;
import es.ual.node.userregistration.domain.UserRole;
import es.ual.node.userregistration.ports.out.RegistrationCodePort;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/** PostgreSQL adapter for registration invitation codes. */
public class PostgresRegistrationCodePort implements RegistrationCodePort {

  private final RegistrationCodeJpaRepository repository;

  /**
   * Creates adapter.
   *
   * @param repository JPA repository
   */
  public PostgresRegistrationCodePort(final RegistrationCodeJpaRepository repository) {
    if (repository == null) {
      throw new IllegalArgumentException("repository must not be null");
    }
    this.repository = repository;
  }

  @Override
  public void save(final RegistrationCode registrationCode) {
    repository.save(toEntity(registrationCode));
  }

  @Override
  public Optional<RegistrationCode> findByCode(final String code) {
    return repository.findById(normalizeCode(code)).map(this::toDomain);
  }

  @Override
  public void markUsed(final String code, final Instant usedAt) {
    if (usedAt == null) {
      throw new IllegalArgumentException("usedAt must not be null");
    }
    final String normalized = normalizeCode(code);
    final Optional<RegistrationCodeJpaEntity> entity = repository.findById(normalized);
    if (entity.isEmpty()) {
      return;
    }
    final RegistrationCodeJpaEntity current = entity.get();
    current.setUsed(true);
    current.setUsedAt(usedAt);
    repository.save(current);
  }

  private RegistrationCodeJpaEntity toEntity(final RegistrationCode domain) {
    final RegistrationCodeJpaEntity entity = new RegistrationCodeJpaEntity();
    entity.setCode(normalizeCode(domain.code()));
    entity.setQuotaMb(domain.quotaMb());
    entity.setRole(domain.role().name());
    entity.setExpiresAt(domain.expiresAt());
    entity.setUsed(domain.used());
    entity.setUsedAt(domain.usedAt());
    entity.setCreatedAt(domain.createdAt());
    return entity;
  }

  private RegistrationCode toDomain(final RegistrationCodeJpaEntity entity) {
    return new RegistrationCode(
        entity.getCode(),
        entity.getQuotaMb(),
        entity.getRole() == null ? UserRole.END_USER : UserRole.parse(entity.getRole()),
        entity.getExpiresAt(),
        entity.isUsed(),
        entity.getUsedAt(),
        entity.getCreatedAt());
  }

  private String normalizeCode(final String code) {
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("code must not be blank");
    }
    return code.trim().toUpperCase(Locale.ROOT);
  }
}
