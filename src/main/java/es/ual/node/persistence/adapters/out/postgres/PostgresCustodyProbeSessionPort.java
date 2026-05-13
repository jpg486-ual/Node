package es.ual.node.persistence.adapters.out.postgres;

import es.ual.node.custodyliveness.domain.CustodyProbeDirection;
import es.ual.node.custodyliveness.domain.CustodyProbeSession;
import es.ual.node.custodyliveness.domain.CustodyProbeStatus;
import es.ual.node.custodyliveness.ports.out.CustodyProbeSessionPort;
import es.ual.node.persistence.jpa.CustodyProbeSessionJpaEntity;
import es.ual.node.persistence.jpa.CustodyProbeSessionJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL-backed custody probe session port. */
public class PostgresCustodyProbeSessionPort implements CustodyProbeSessionPort {

  private final CustodyProbeSessionJpaRepository repository;

  /**
   * Creates adapter.
   *
   * @param repository session repository
   */
  public PostgresCustodyProbeSessionPort(final CustodyProbeSessionJpaRepository repository) {
    if (repository == null) {
      throw new IllegalArgumentException("repository must not be null");
    }
    this.repository = repository;
  }

  @Override
  @Transactional
  public void save(final CustodyProbeSession session) {
    if (session == null || session.sessionId() == null || session.sessionId().isBlank()) {
      throw new IllegalArgumentException("session with non-blank sessionId is required");
    }
    repository.save(toEntity(session));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<CustodyProbeSession> findById(final String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      return Optional.empty();
    }
    return repository.findById(sessionId.trim()).map(this::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public List<CustodyProbeSession> findByRemoteNodeId(final String remoteNodeId) {
    if (remoteNodeId == null || remoteNodeId.isBlank()) {
      return List.of();
    }
    return repository.findByRemoteNodeIdOrderByUpdatedAtDesc(remoteNodeId.trim()).stream()
        .map(this::toDomain)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<CustodyProbeSession> findAll() {
    return repository.findAllByOrderByUpdatedAtDescCreatedAtDesc().stream()
        .map(this::toDomain)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<CustodyProbeSession> findDueOutbound(final Instant now, final int limit) {
    if (now == null || limit <= 0) {
      return List.of();
    }
    return repository
        .findDueByDirection(CustodyProbeDirection.OUTBOUND.name(), now, PageRequest.of(0, limit))
        .stream()
        .map(this::toDomain)
        .toList();
  }

  private CustodyProbeSessionJpaEntity toEntity(final CustodyProbeSession session) {
    final CustodyProbeSessionJpaEntity entity = new CustodyProbeSessionJpaEntity();
    entity.setSessionId(session.sessionId());
    entity.setRemoteNodeId(session.remoteNodeId());
    entity.setDirection(session.direction().name());
    entity.setStatus(session.status().name());
    entity.setAttemptCount(session.attemptCount());
    entity.setLastSuccessAt(session.lastSuccessAt());
    entity.setLastAttemptAt(session.lastAttemptAt());
    entity.setNextAttemptAt(session.nextAttemptAt());
    entity.setLastError(session.lastError());
    entity.setReverseProbeCooldownUntil(session.reverseProbeCooldownUntil());
    entity.setCreatedAt(session.createdAt());
    entity.setUpdatedAt(session.updatedAt());
    entity.setRemoteTutorBaseUrl(session.remoteTutorBaseUrl());
    return entity;
  }

  private CustodyProbeSession toDomain(final CustodyProbeSessionJpaEntity entity) {
    return new CustodyProbeSession(
        entity.getSessionId(),
        entity.getRemoteNodeId(),
        CustodyProbeDirection.valueOf(entity.getDirection()),
        CustodyProbeStatus.valueOf(entity.getStatus()),
        entity.getAttemptCount(),
        entity.getLastSuccessAt(),
        entity.getLastAttemptAt(),
        entity.getNextAttemptAt(),
        entity.getLastError(),
        entity.getReverseProbeCooldownUntil(),
        entity.getCreatedAt(),
        entity.getUpdatedAt(),
        entity.getRemoteTutorBaseUrl());
  }
}
