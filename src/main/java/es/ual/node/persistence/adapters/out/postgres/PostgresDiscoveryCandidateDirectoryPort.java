package es.ual.node.persistence.adapters.out.postgres;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.ual.node.discovery.application.DiscoveryCandidateDirectoryProperties;
import es.ual.node.discovery.domain.DiscoveryCandidateProfile;
import es.ual.node.discovery.ports.out.DiscoveryCandidateDirectoryPort;
import es.ual.node.persistence.jpa.DiscoveryCandidateJpaEntity;
import es.ual.node.persistence.jpa.DiscoveryCandidateJpaRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;

/** PostgreSQL-backed adapter for durable discovery candidate directory. */
public class PostgresDiscoveryCandidateDirectoryPort implements DiscoveryCandidateDirectoryPort {

  private static final TypeReference<Set<Long>> BUCKET_SET_TYPE = new TypeReference<>() {};

  private final DiscoveryCandidateJpaRepository repository;
  private final ObjectMapper objectMapper;
  private final DiscoveryCandidateDirectoryProperties properties;
  private final Clock clock;

  /** Creates adapter. */
  public PostgresDiscoveryCandidateDirectoryPort(
      final DiscoveryCandidateJpaRepository repository,
      final ObjectMapper objectMapper,
      final DiscoveryCandidateDirectoryProperties properties,
      final Clock clock) {
    if (repository == null || objectMapper == null || properties == null || clock == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    this.repository = repository;
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.clock = clock;
  }

  @Override
  public java.util.List<DiscoveryCandidateProfile> findActiveCandidates() {
    final Instant freshnessThreshold =
        clock.instant().minusSeconds(properties.getFreshnessSeconds());
    return repository.findActive(freshnessThreshold, properties.getMinimumAvailableBytes()).stream()
        .map(this::toDomain)
        .toList();
  }

  @Override
  public long countActiveCandidates() {
    final Instant freshnessThreshold =
        clock.instant().minusSeconds(properties.getFreshnessSeconds());
    return repository.countActive(freshnessThreshold, properties.getMinimumAvailableBytes());
  }

  @Override
  public void upsertCandidate(final DiscoveryCandidateProfile profile) {
    if (profile == null) {
      throw new IllegalArgumentException("profile must not be null");
    }

    final Instant now = clock.instant();
    final DiscoveryCandidateJpaEntity entity =
        repository.findById(profile.nodeId()).orElseGet(DiscoveryCandidateJpaEntity::new);

    entity.setNodeId(profile.nodeId());
    entity.setFailureDomain(profile.failureDomain());
    entity.setBaseUrl(profile.baseUrl());
    entity.setOriginalRequestedBucket(profile.originalRequestedBucket());
    entity.setAcceptedBucketsJson(serializeAcceptedBuckets(profile.acceptedBuckets()));
    entity.setHealthy(true);
    entity.setAvailableBytes(Long.MAX_VALUE);
    entity.setLastSeenAt(now);
    entity.setUpdatedAt(now);

    repository.save(entity);
  }

  @Override
  public void removeCandidate(final String nodeId) {
    if (nodeId == null || nodeId.isBlank()) {
      throw new IllegalArgumentException("nodeId must not be blank");
    }
    repository.deleteById(nodeId.trim());
  }

  @Override
  public int deleteStale(final Instant staleBefore) {
    if (staleBefore == null) {
      throw new IllegalArgumentException("staleBefore must not be null");
    }
    return repository.deleteByLastSeenAtBefore(staleBefore);
  }

  private DiscoveryCandidateProfile toDomain(final DiscoveryCandidateJpaEntity entity) {
    return new DiscoveryCandidateProfile(
        entity.getNodeId(),
        entity.getFailureDomain(),
        entity.getBaseUrl(),
        entity.getOriginalRequestedBucket(),
        deserializeAcceptedBuckets(entity.getAcceptedBucketsJson()));
  }

  private String serializeAcceptedBuckets(final Set<Long> acceptedBuckets) {
    try {
      return objectMapper.writeValueAsString(acceptedBuckets == null ? Set.of() : acceptedBuckets);
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to serialize accepted buckets", exception);
    }
  }

  private Set<Long> deserializeAcceptedBuckets(final String value) {
    try {
      if (value == null || value.isBlank()) {
        return Set.of();
      }
      return objectMapper.readValue(value, BUCKET_SET_TYPE);
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to deserialize accepted buckets", exception);
    }
  }
}
