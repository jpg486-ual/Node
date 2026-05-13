package es.ual.node.discovery.adapters.out.memory;

import es.ual.node.discovery.domain.DiscoveryRetryRequest;
import es.ual.node.discovery.domain.DiscoveryRetryStatus;
import es.ual.node.discovery.ports.out.DiscoveryRetryQueuePort;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory retry queue adapter. */
public class InMemoryDiscoveryRetryQueuePort implements DiscoveryRetryQueuePort {

  private final Map<String, DiscoveryRetryRequest> byId = new ConcurrentHashMap<>();

  @Override
  public DiscoveryRetryRequest save(final DiscoveryRetryRequest retryRequest) {
    byId.put(retryRequest.id(), retryRequest);
    return retryRequest;
  }

  @Override
  public Optional<DiscoveryRetryRequest> findById(final String id) {
    return Optional.ofNullable(byId.get(id));
  }

  @Override
  public List<DiscoveryRetryRequest> findDue(final Instant now, final int limit) {
    return byId.values().stream()
        .filter(entry -> entry.status() == DiscoveryRetryStatus.PENDING)
        .filter(entry -> !entry.nextAttemptAt().isAfter(now))
        .sorted(
            Comparator.comparing(DiscoveryRetryRequest::nextAttemptAt)
                .thenComparing(DiscoveryRetryRequest::createdAt))
        .limit(limit)
        .toList();
  }

  @Override
  public List<DiscoveryRetryRequest> findAll() {
    return byId.values().stream()
        .sorted(
            Comparator.comparing(DiscoveryRetryRequest::updatedAt, Comparator.reverseOrder())
                .thenComparing(DiscoveryRetryRequest::createdAt, Comparator.reverseOrder())
                .thenComparing(DiscoveryRetryRequest::id))
        .toList();
  }

  @Override
  public long countByStatus(final DiscoveryRetryStatus status) {
    if (status == null) {
      throw new IllegalArgumentException("status must not be null");
    }
    return byId.values().stream().filter(entry -> entry.status() == status).count();
  }

  @Override
  public void deleteTerminalOlderThan(final Instant threshold) {
    byId.entrySet()
        .removeIf(
            entry -> {
              final DiscoveryRetryRequest value = entry.getValue();
              final boolean terminal =
                  value.status() == DiscoveryRetryStatus.RESOLVED
                      || value.status() == DiscoveryRetryStatus.FAILED;
              return terminal && value.updatedAt().isBefore(threshold);
            });
  }
}
