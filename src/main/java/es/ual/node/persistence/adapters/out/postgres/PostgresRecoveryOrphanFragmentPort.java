package es.ual.node.persistence.adapters.out.postgres;

import es.ual.node.persistence.jpa.RecoveryOrphanFragmentJpaEntity;
import es.ual.node.persistence.jpa.RecoveryOrphanFragmentJpaRepository;
import es.ual.node.recovery.domain.RecoveryOrphanFragment;
import es.ual.node.recovery.ports.out.RecoveryOrphanFragmentPort;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL-backed recovery orphan fragment port. */
public class PostgresRecoveryOrphanFragmentPort implements RecoveryOrphanFragmentPort {

  private final RecoveryOrphanFragmentJpaRepository repository;

  public PostgresRecoveryOrphanFragmentPort(final RecoveryOrphanFragmentJpaRepository repository) {
    if (repository == null) {
      throw new IllegalArgumentException("repository must not be null");
    }
    this.repository = repository;
  }

  @Override
  @Transactional
  public void save(final RecoveryOrphanFragment fragment) {
    if (fragment == null) {
      throw new IllegalArgumentException("fragment must not be null");
    }
    repository.save(toEntity(fragment));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<RecoveryOrphanFragment> findByFragmentId(final String fragmentId) {
    if (fragmentId == null || fragmentId.isBlank()) {
      return Optional.empty();
    }
    return repository.findById(fragmentId.trim()).map(this::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public List<RecoveryOrphanFragment> findByRequesterNodeId(final String requesterNodeId) {
    if (requesterNodeId == null || requesterNodeId.isBlank()) {
      return List.of();
    }
    return repository.findByRequesterNodeId(requesterNodeId.trim()).stream()
        .map(this::toDomain)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<RecoveryOrphanFragment> findAll() {
    return repository.findAllByOrderByStoredAtDesc().stream().map(this::toDomain).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<String> findAllFragmentIds(final int limit) {
    final int safeLimit = Math.max(1, limit);
    return repository.findAllFragmentIds(PageRequest.of(0, safeLimit));
  }

  @Override
  @Transactional
  public void deleteByFragmentId(final String fragmentId) {
    if (fragmentId == null || fragmentId.isBlank()) {
      return;
    }
    repository.deleteById(fragmentId.trim());
  }

  private RecoveryOrphanFragmentJpaEntity toEntity(final RecoveryOrphanFragment fragment) {
    RecoveryOrphanFragmentJpaEntity entity = new RecoveryOrphanFragmentJpaEntity();
    entity.setFragmentId(fragment.fragmentId());
    entity.setAgreementId(fragment.agreementId());
    entity.setRequesterNodeId(fragment.requesterNodeId());
    entity.setChecksumAlgorithm(fragment.checksumAlgorithm());
    entity.setChecksum(fragment.checksum());
    entity.setSizeBytes(fragment.sizeBytes());
    entity.setStoredAt(fragment.storedAt());
    return entity;
  }

  private RecoveryOrphanFragment toDomain(final RecoveryOrphanFragmentJpaEntity entity) {
    return new RecoveryOrphanFragment(
        entity.getFragmentId(),
        entity.getAgreementId(),
        entity.getRequesterNodeId(),
        entity.getChecksumAlgorithm(),
        entity.getChecksum(),
        entity.getSizeBytes(),
        entity.getStoredAt());
  }
}
