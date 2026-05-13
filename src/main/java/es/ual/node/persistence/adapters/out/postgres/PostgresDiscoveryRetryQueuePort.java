package es.ual.node.persistence.adapters.out.postgres;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.ual.node.discovery.domain.DiscoveryRetryRequest;
import es.ual.node.discovery.domain.DiscoveryRetryStatus;
import es.ual.node.discovery.ports.out.DiscoveryRetryQueuePort;
import es.ual.node.persistence.jpa.DiscoveryRetryRequestJpaEntity;
import es.ual.node.persistence.jpa.DiscoveryRetryRequestJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL-backed adapter for discovery retry queue. */
public class PostgresDiscoveryRetryQueuePort implements DiscoveryRetryQueuePort {

  private static final TypeReference<Map<String, Integer>> PLAN_MAP_TYPE = new TypeReference<>() {};

  private final DiscoveryRetryRequestJpaRepository repository;
  private final ObjectMapper objectMapper;

  /** Creates adapter. */
  public PostgresDiscoveryRetryQueuePort(
      final DiscoveryRetryRequestJpaRepository repository, final ObjectMapper objectMapper) {
    if (repository == null || objectMapper == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  @Override
  public DiscoveryRetryRequest save(final DiscoveryRetryRequest retryRequest) {
    final DiscoveryRetryRequestJpaEntity entity = new DiscoveryRetryRequestJpaEntity();
    entity.setId(retryRequest.id());
    entity.setNodeId(retryRequest.request().nodeId());
    entity.setFailureDomain(retryRequest.request().failureDomain());
    entity.setRequestedBucket(retryRequest.request().requestedBucket());
    entity.setRatio(retryRequest.request().ratio());
    entity.setMaxCandidates(retryRequest.request().maxCandidates());
    entity.setTargetFailureDomain(retryRequest.request().targetFailureDomain());
    entity.setDistributionPlanJson(serializePlan(retryRequest.request().distributionPlan()));
    entity.setStatus(retryRequest.status().name());
    entity.setAttemptCount(retryRequest.attemptCount());
    entity.setNextAttemptAt(retryRequest.nextAttemptAt());
    entity.setCreatedAt(retryRequest.createdAt());
    entity.setUpdatedAt(retryRequest.updatedAt());
    entity.setResolvedAt(retryRequest.resolvedAt());
    entity.setResolvedCandidateCount(retryRequest.resolvedCandidateCount());
    entity.setLastError(retryRequest.lastError());
    return toDomain(repository.save(entity));
  }

  @Override
  public Optional<DiscoveryRetryRequest> findById(final String id) {
    return repository.findById(id).map(this::toDomain);
  }

  @Override
  public List<DiscoveryRetryRequest> findDue(final Instant now, final int limit) {
    final int boundedLimit = Math.max(1, limit);
    return repository.findDue(now, PageRequest.of(0, boundedLimit)).stream()
        .map(this::toDomain)
        .toList();
  }

  @Override
  public List<DiscoveryRetryRequest> findAll() {
    return repository.findAllByOrderByUpdatedAtDescCreatedAtDesc().stream()
        .map(this::toDomain)
        .toList();
  }

  @Override
  public long countByStatus(final DiscoveryRetryStatus status) {
    if (status == null) {
      throw new IllegalArgumentException("status must not be null");
    }
    return repository.countByStatus(status.name());
  }

  @Override
  @Transactional
  public void deleteTerminalOlderThan(final Instant threshold) {
    repository.deleteTerminalOlderThan(threshold);
  }

  private DiscoveryRetryRequest toDomain(final DiscoveryRetryRequestJpaEntity entity) {
    return new DiscoveryRetryRequest(
        entity.getId(),
        DiscoveryRetryRequest.toDiscoveryRequest(
            entity.getNodeId(),
            entity.getFailureDomain(),
            entity.getRequestedBucket(),
            entity.getRatio(),
            entity.getMaxCandidates(),
            entity.getTargetFailureDomain(),
            deserializePlan(entity.getDistributionPlanJson())),
        DiscoveryRetryStatus.valueOf(entity.getStatus()),
        entity.getAttemptCount(),
        entity.getNextAttemptAt(),
        entity.getCreatedAt(),
        entity.getUpdatedAt(),
        entity.getResolvedAt(),
        entity.getResolvedCandidateCount(),
        entity.getLastError());
  }

  private String serializePlan(final Map<String, Integer> value) {
    try {
      return objectMapper.writeValueAsString(value == null ? Map.of() : value);
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to serialize distribution plan", exception);
    }
  }

  private Map<String, Integer> deserializePlan(final String value) {
    try {
      if (value == null || value.isBlank()) {
        return Map.of();
      }
      return objectMapper.readValue(value, PLAN_MAP_TYPE);
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to deserialize distribution plan", exception);
    }
  }
}
