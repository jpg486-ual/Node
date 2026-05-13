package es.ual.node.discovery.adapters.out.memory;

import es.ual.node.discovery.domain.DiscoveryCandidateProfile;
import es.ual.node.discovery.ports.out.DiscoveryCandidateDirectoryPort;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory candidate directory for discovery. Tracks {@code lastSeenAt} per candidate so the
 * cleanup worker can prune stale entries symmetrically to the Postgres adapter.
 */
public class InMemoryDiscoveryCandidateDirectoryAdapter implements DiscoveryCandidateDirectoryPort {

  private final ConcurrentHashMap<String, StoredCandidate> candidates = new ConcurrentHashMap<>();
  private final Clock clock;

  /** Creates adapter with a system UTC clock. Used by tests that don't need controlled time. */
  public InMemoryDiscoveryCandidateDirectoryAdapter() {
    this(Clock.systemUTC());
  }

  /**
   * Creates adapter with explicit clock.
   *
   * @param clock clock used to stamp lastSeenAt on upserts
   */
  public InMemoryDiscoveryCandidateDirectoryAdapter(final Clock clock) {
    if (clock == null) {
      throw new IllegalArgumentException("clock must not be null");
    }
    this.clock = clock;
  }

  /**
   * Registers or updates candidate profile by replacing existing node entry.
   *
   * @param profile candidate profile
   */
  public void upsert(final DiscoveryCandidateProfile profile) {
    upsertCandidate(profile);
  }

  /** {@inheritDoc} */
  @Override
  public void upsertCandidate(final DiscoveryCandidateProfile profile) {
    if (profile == null) {
      throw new IllegalArgumentException("profile must not be null");
    }
    candidates.put(profile.nodeId(), new StoredCandidate(profile, clock.instant()));
  }

  /** Clears registered candidates. */
  public void clear() {
    candidates.clear();
  }

  /** {@inheritDoc} */
  @Override
  public void removeCandidate(final String nodeId) {
    if (nodeId == null || nodeId.isBlank()) {
      throw new IllegalArgumentException("nodeId must not be blank");
    }
    candidates.remove(nodeId.trim());
  }

  /** {@inheritDoc} */
  @Override
  public List<DiscoveryCandidateProfile> findActiveCandidates() {
    final List<DiscoveryCandidateProfile> result = new ArrayList<>(candidates.size());
    for (StoredCandidate stored : candidates.values()) {
      result.add(stored.profile());
    }
    return List.copyOf(result);
  }

  /** {@inheritDoc} */
  @Override
  public long countActiveCandidates() {
    return candidates.size();
  }

  /** {@inheritDoc} */
  @Override
  public int deleteStale(final Instant staleBefore) {
    if (staleBefore == null) {
      throw new IllegalArgumentException("staleBefore must not be null");
    }
    int removed = 0;
    for (var iterator = candidates.entrySet().iterator(); iterator.hasNext(); ) {
      final var entry = iterator.next();
      if (entry.getValue().lastSeenAt().isBefore(staleBefore)) {
        iterator.remove();
        removed++;
      }
    }
    return removed;
  }

  private record StoredCandidate(DiscoveryCandidateProfile profile, Instant lastSeenAt) {}
}
