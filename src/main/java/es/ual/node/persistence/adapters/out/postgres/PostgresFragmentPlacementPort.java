package es.ual.node.persistence.adapters.out.postgres;

import es.ual.node.filesystem.domain.FragmentHealthStatus;
import es.ual.node.filesystem.domain.FragmentPlacement;
import es.ual.node.filesystem.ports.out.FragmentPlacementPort;
import es.ual.node.persistence.jpa.ClientFragmentPlacementJpaEntity;
import es.ual.node.persistence.jpa.ClientFragmentPlacementJpaRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL adapter for {@link FragmentPlacementPort}. */
public class PostgresFragmentPlacementPort implements FragmentPlacementPort {

  private final ClientFragmentPlacementJpaRepository repository;

  /** Creates adapter. */
  public PostgresFragmentPlacementPort(final ClientFragmentPlacementJpaRepository repository) {
    if (repository == null) {
      throw new IllegalArgumentException("repository must not be null");
    }
    this.repository = repository;
  }

  @Override
  public void save(final FragmentPlacement placement) {
    if (placement == null) {
      throw new IllegalArgumentException("placement must not be null");
    }
    final ClientFragmentPlacementJpaEntity entity = new ClientFragmentPlacementJpaEntity();
    entity.setFileId(placement.fileId());
    entity.setFragmentId(placement.fragmentId());
    entity.setBlockIndex(placement.blockIndex());
    entity.setFragmentIndex(placement.fragmentIndex());
    entity.setParity(placement.parity());
    entity.setCustodianNodeId(placement.custodianNodeId());
    entity.setCustodianBaseUrl(placement.custodianBaseUrl());
    entity.setAgreementId(placement.agreementId());
    entity.setFragmentChecksum(placement.fragmentChecksum());
    entity.setFragmentSizeBytes(placement.fragmentSizeBytes());
    entity.setCreatedAt(placement.createdAt());
    entity.setHealthStatus(placement.healthStatus().name());
    entity.setLastCheckAt(placement.lastCheckAt());
    entity.setConsecutiveFailures(placement.consecutiveFailures());
    repository.save(entity);
  }

  @Override
  public List<FragmentPlacement> findByFileId(final String fileId) {
    if (fileId == null || fileId.isBlank()) {
      return List.of();
    }
    return repository.findAllByFileIdOrderByIndex(fileId.trim()).stream()
        .map(this::toDomain)
        .toList();
  }

  @Override
  @Transactional
  public void deleteByFileId(final String fileId) {
    if (fileId == null || fileId.isBlank()) {
      return;
    }
    repository.deleteAllByFileId(fileId.trim());
  }

  @Override
  public List<FragmentPlacement> findAll() {
    return repository.findAllOrderByKey().stream().map(this::toDomain).toList();
  }

  @Override
  @Transactional
  public void deleteByFileIdAndFragmentId(final String fileId, final String fragmentId) {
    if (fileId == null || fileId.isBlank() || fragmentId == null || fragmentId.isBlank()) {
      return;
    }
    repository.deleteByFileIdAndFragmentId(fileId.trim(), fragmentId.trim());
  }

  @Override
  public Optional<FragmentPlacement> findByFragmentId(final String fragmentId) {
    if (fragmentId == null || fragmentId.isBlank()) {
      return Optional.empty();
    }
    return repository.findByFragmentId(fragmentId.trim()).map(this::toDomain);
  }

  @Override
  public List<FragmentPlacement> findByCustodianNodeId(final String custodianNodeId) {
    if (custodianNodeId == null || custodianNodeId.isBlank()) {
      return List.of();
    }
    return repository.findAllByCustodianNodeId(custodianNodeId.trim()).stream()
        .map(this::toDomain)
        .toList();
  }

  @Override
  public List<FragmentPlacement> findByCustodianBaseUrl(final String custodianBaseUrl) {
    if (custodianBaseUrl == null || custodianBaseUrl.isBlank()) {
      return List.of();
    }
    return repository.findAllByCustodianBaseUrl(custodianBaseUrl.trim()).stream()
        .map(this::toDomain)
        .toList();
  }

  @Override
  @Transactional
  public void updateHealth(final FragmentPlacement updated) {
    if (updated == null) {
      throw new IllegalArgumentException("updated must not be null");
    }
    repository.updateHealthByFileIdAndFragmentId(
        updated.fileId(),
        updated.fragmentId(),
        updated.healthStatus().name(),
        updated.lastCheckAt(),
        updated.consecutiveFailures());
  }

  private FragmentPlacement toDomain(final ClientFragmentPlacementJpaEntity entity) {
    return new FragmentPlacement(
        entity.getFileId(),
        entity.getFragmentId(),
        entity.getBlockIndex(),
        entity.getFragmentIndex(),
        entity.isParity(),
        entity.getCustodianNodeId(),
        entity.getCustodianBaseUrl(),
        entity.getAgreementId(),
        entity.getFragmentChecksum(),
        entity.getFragmentSizeBytes(),
        entity.getCreatedAt(),
        FragmentHealthStatus.valueOf(entity.getHealthStatus()),
        entity.getLastCheckAt(),
        entity.getConsecutiveFailures());
  }
}
