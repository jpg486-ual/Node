package es.ual.node.discovery.ports.out;

import es.ual.node.discovery.domain.DiscoveryCandidateProfile;
import java.time.Instant;
import java.util.List;

/** Outbound port for obtaining active candidate profiles. */
public interface DiscoveryCandidateDirectoryPort {

  /**
   * Returns active candidate profiles.
   *
   * @return active candidate profiles
   */
  List<DiscoveryCandidateProfile> findActiveCandidates();

  /**
   * Counts active candidate profiles.
   *
   * @return active candidates count
   */
  long countActiveCandidates();

  /**
   * Creates or updates one candidate profile.
   *
   * @param profile candidate profile to persist
   */
  void upsertCandidate(DiscoveryCandidateProfile profile);

  /**
   * Removes one candidate profile by node identifier.
   *
   * @param nodeId candidate node identifier
   */
  void removeCandidate(String nodeId);

  /**
   * Removes all candidate profiles whose {@code lastSeenAt} is strictly before the given threshold
   * Invoked by the supernode-side {@link
   * es.ual.node.discovery.application.DiscoveryCandidateCleanupWorker} to garbage-collect zombie
   * rows; the {@code findActive} freshness filter already hides them, this just keeps the row count
   * bounded.
   *
   * @param staleBefore threshold instant
   * @return count of removed rows
   */
  int deleteStale(Instant staleBefore);
}
