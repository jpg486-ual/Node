package es.ual.node.persistence.adapters.out.postgres;

import es.ual.node.custodyliveness.domain.OriginCustodianHealth;
import es.ual.node.custodyliveness.ports.out.OriginCustodianHealthPort;
import es.ual.node.persistence.jpa.OriginCustodianHealthJpaEntity;
import es.ual.node.persistence.jpa.OriginCustodianHealthJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** Postgres adapter para {@link OriginCustodianHealthPort}. */
public class PostgresOriginCustodianHealthPort implements OriginCustodianHealthPort {

  private final OriginCustodianHealthJpaRepository repository;

  public PostgresOriginCustodianHealthPort(final OriginCustodianHealthJpaRepository repository) {
    if (repository == null) {
      throw new IllegalArgumentException("repository must not be null");
    }
    this.repository = repository;
  }

  @Override
  @Transactional
  public void upsertOnInboundProbe(
      final String custodianNodeId, final String custodianBaseUrl, final Instant now) {
    if (custodianNodeId == null || custodianNodeId.isBlank()) {
      throw new IllegalArgumentException("custodianNodeId must not be blank");
    }
    if (custodianBaseUrl == null || custodianBaseUrl.isBlank()) {
      throw new IllegalArgumentException("custodianBaseUrl must not be blank");
    }
    if (now == null) {
      throw new IllegalArgumentException("now must not be null");
    }
    final OriginCustodianHealthJpaEntity entity =
        repository
            .findById(custodianNodeId.trim())
            .orElseGet(
                () -> {
                  final OriginCustodianHealthJpaEntity fresh = new OriginCustodianHealthJpaEntity();
                  fresh.setCustodianNodeId(custodianNodeId.trim());
                  return fresh;
                });
    entity.setCustodianBaseUrl(custodianBaseUrl.trim());
    entity.setLastInboundProbeAt(now);
    entity.setConsecutiveFailures(0);
    entity.setUpdatedAt(now);
    repository.save(entity);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<OriginCustodianHealth> findById(final String custodianNodeId) {
    if (custodianNodeId == null || custodianNodeId.isBlank()) {
      return Optional.empty();
    }
    return repository.findById(custodianNodeId.trim()).map(this::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public List<OriginCustodianHealth> findSilentCustodians(final Instant threshold) {
    if (threshold == null) {
      return List.of();
    }
    return repository.findSilent(threshold).stream().map(this::toDomain).toList();
  }

  @Override
  @Transactional
  public void save(final OriginCustodianHealth record) {
    if (record == null) {
      throw new IllegalArgumentException("record must not be null");
    }
    final OriginCustodianHealthJpaEntity entity =
        repository
            .findById(record.custodianNodeId())
            .orElseGet(OriginCustodianHealthJpaEntity::new);
    entity.setCustodianNodeId(record.custodianNodeId());
    entity.setCustodianBaseUrl(record.custodianBaseUrl());
    entity.setLastInboundProbeAt(record.lastInboundProbeAt());
    entity.setConsecutiveFailures(record.consecutiveFailures());
    entity.setUpdatedAt(record.updatedAt());
    repository.save(entity);
  }

  private OriginCustodianHealth toDomain(final OriginCustodianHealthJpaEntity entity) {
    return new OriginCustodianHealth(
        entity.getCustodianNodeId(),
        entity.getCustodianBaseUrl(),
        entity.getLastInboundProbeAt(),
        entity.getConsecutiveFailures(),
        entity.getUpdatedAt());
  }
}
