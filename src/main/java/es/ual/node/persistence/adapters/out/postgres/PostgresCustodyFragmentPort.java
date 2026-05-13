package es.ual.node.persistence.adapters.out.postgres;

import es.ual.node.fragmentstorage.domain.CustodyFragment;
import es.ual.node.fragmentstorage.ports.out.CustodyFragmentPort;
import es.ual.node.persistence.jpa.CustodyFragmentJpaEntity;
import es.ual.node.persistence.jpa.CustodyFragmentJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL-backed custody fragment port.
 *
 * <p>Dispara el flujo de escalation vía {@code CustodyExpiryEscalationWorker} que intenta
 * RETURN_TO_TUTOR antes de eliminar (con fallback de mantención + warning si tutor unreachable).
 *
 * <p>Eliminación física custody-side ahora ocurre solo en 3 caminos explícitos:
 *
 * <ul>
 *   <li>{@code TutorReturnCustodyEscalationPort.handleUnresponsive} tras POST 201/409 al tutor
 *       (migración a {@code recovery_orphan_fragment}).
 *   <li>{@code CustodyFragmentLifecycleAdapter.decommissionCustody} tras keep-list whitelist diff
 *       (fragment NO en whitelist del origen).
 *   <li>{@code FragmentCustodyService.releaseCustody} comando explícito.
 * </ul>
 */
public class PostgresCustodyFragmentPort implements CustodyFragmentPort {

  private final CustodyFragmentJpaRepository repository;

  public PostgresCustodyFragmentPort(final CustodyFragmentJpaRepository repository) {
    if (repository == null) {
      throw new IllegalArgumentException("repository must not be null");
    }
    this.repository = repository;
  }

  @Override
  @Transactional
  public void save(final CustodyFragment fragment) {
    if (fragment == null) {
      throw new IllegalArgumentException("fragment must not be null");
    }
    repository.save(toEntity(fragment));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<CustodyFragment> findByFragmentId(final String fragmentId) {
    if (fragmentId == null || fragmentId.isBlank()) {
      return Optional.empty();
    }
    return repository.findById(fragmentId.trim()).map(this::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public List<CustodyFragment> findByRequesterNodeId(final String requesterNodeId) {
    if (requesterNodeId == null || requesterNodeId.isBlank()) {
      return List.of();
    }
    return repository.findByRequesterNodeId(requesterNodeId.trim()).stream()
        .map(this::toDomain)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<CustodyFragment> findAll() {
    return repository.findAllByOrderByStoredAtDesc().stream().map(this::toDomain).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<CustodyFragment> findExpired(final Instant threshold, final int limit) {
    if (threshold == null) {
      throw new IllegalArgumentException("threshold must not be null");
    }
    final int capped = Math.max(1, limit);
    return repository
        .findByExpiresAtBeforeOrderByExpiresAtAsc(threshold, PageRequest.of(0, capped))
        .stream()
        .map(this::toDomain)
        .toList();
  }

  @Override
  @Transactional
  public void deleteByFragmentId(final String fragmentId) {
    if (fragmentId == null || fragmentId.isBlank()) {
      return;
    }
    repository.deleteById(fragmentId.trim());
  }

  @Override
  @Transactional(readOnly = true)
  public long totalSizeBytes() {
    return repository.sumSizeBytes();
  }

  private CustodyFragmentJpaEntity toEntity(final CustodyFragment fragment) {
    CustodyFragmentJpaEntity entity = new CustodyFragmentJpaEntity();
    entity.setFragmentId(fragment.fragmentId());
    entity.setAgreementId(fragment.agreementId());
    entity.setRequesterNodeId(fragment.requesterNodeId());
    entity.setChecksumAlgorithm(fragment.checksumAlgorithm());
    entity.setChecksum(fragment.checksum());
    entity.setSizeBytes(fragment.sizeBytes());
    entity.setStoredAt(fragment.storedAt());
    entity.setExpiresAt(fragment.expiresAt());
    return entity;
  }

  private CustodyFragment toDomain(final CustodyFragmentJpaEntity entity) {
    return new CustodyFragment(
        entity.getFragmentId(),
        entity.getAgreementId(),
        entity.getRequesterNodeId(),
        entity.getChecksumAlgorithm(),
        entity.getChecksum(),
        entity.getSizeBytes(),
        entity.getStoredAt(),
        entity.getExpiresAt());
  }
}
