package es.ual.node.discovery.ports.out;

import es.ual.node.discovery.domain.DiscoveryRetryRequest;
import es.ual.node.discovery.domain.DiscoveryRetryStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence boundary for durable discovery retry requests. */
public interface DiscoveryRetryQueuePort {

  /**
   * Saves or updates a retry request.
   *
   * @param retryRequest retry request
   * @return persisted retry request
   */
  DiscoveryRetryRequest save(DiscoveryRetryRequest retryRequest);

  /**
   * Finds request by id.
   *
   * @param id request id
   * @return retry request when present
   */
  Optional<DiscoveryRetryRequest> findById(String id);

  /**
   * Returns due requests for retry processing.
   *
   * @param now current instant
   * @param limit max rows
   * @return due retry requests
   */
  List<DiscoveryRetryRequest> findDue(Instant now, int limit);

  /**
   * Returns all persisted retry requests ordered by latest updates first.
   *
   * @return retry requests
   */
  List<DiscoveryRetryRequest> findAll();

  /**
   * Counts retry requests by status.
   *
   * @param status retry status
   * @return total number of requests in status
   */
  long countByStatus(DiscoveryRetryStatus status);

  /**
   * Deletes resolved or failed requests older than threshold.
   *
   * @param threshold threshold instant
   */
  void deleteTerminalOlderThan(Instant threshold);
}
