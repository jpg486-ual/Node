package es.ual.node.persistence.adapters.out.postgres;

import es.ual.node.persistence.jpa.RecoveryOrphanFragmentPayloadJpaEntity;
import es.ual.node.persistence.jpa.RecoveryOrphanFragmentPayloadJpaRepository;
import es.ual.node.recovery.ports.out.RecoveryOrphanFragmentPayloadPort;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL-backed recovery-domain orphan fragment payload bytes adapter. */
public class PostgresRecoveryOrphanFragmentPayloadPort
    implements RecoveryOrphanFragmentPayloadPort {

  private final RecoveryOrphanFragmentPayloadJpaRepository repository;

  public PostgresRecoveryOrphanFragmentPayloadPort(
      final RecoveryOrphanFragmentPayloadJpaRepository repository) {
    if (repository == null) {
      throw new IllegalArgumentException("repository must not be null");
    }
    this.repository = repository;
  }

  @Override
  @Transactional
  public void save(final String fragmentId, final byte[] payload) {
    if (fragmentId == null || fragmentId.isBlank()) {
      throw new IllegalArgumentException("fragmentId must not be blank");
    }
    if (payload == null || payload.length == 0) {
      throw new IllegalArgumentException("payload must not be null or empty");
    }
    final RecoveryOrphanFragmentPayloadJpaEntity entity =
        new RecoveryOrphanFragmentPayloadJpaEntity();
    entity.setFragmentId(fragmentId.trim());
    entity.setPayload(payload.clone());
    repository.save(entity);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<byte[]> findByFragmentId(final String fragmentId) {
    if (fragmentId == null || fragmentId.isBlank()) {
      return Optional.empty();
    }
    return repository
        .findById(fragmentId.trim())
        .map(RecoveryOrphanFragmentPayloadJpaEntity::getPayload)
        .map(byte[]::clone);
  }

  @Override
  @Transactional(readOnly = true)
  public boolean exists(final String fragmentId) {
    if (fragmentId == null || fragmentId.isBlank()) {
      return false;
    }
    return repository.existsById(fragmentId.trim());
  }

  @Override
  @Transactional
  public void deleteByFragmentId(final String fragmentId) {
    if (fragmentId == null || fragmentId.isBlank()) {
      return;
    }
    repository.deleteById(fragmentId.trim());
  }
}
