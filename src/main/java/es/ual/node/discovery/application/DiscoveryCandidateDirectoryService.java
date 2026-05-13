package es.ual.node.discovery.application;

import es.ual.node.discovery.domain.DiscoveryCandidateProfile;
import es.ual.node.discovery.ports.out.DiscoveryCandidateDirectoryPort;
import java.util.List;

/** Application service for discovery candidate directory operations. */
public class DiscoveryCandidateDirectoryService {

  private final DiscoveryCandidateDirectoryPort candidateDirectoryPort;

  /**
   * Creates service.
   *
   * @param candidateDirectoryPort candidate directory port
   */
  public DiscoveryCandidateDirectoryService(
      final DiscoveryCandidateDirectoryPort candidateDirectoryPort) {
    if (candidateDirectoryPort == null) {
      throw new IllegalArgumentException("candidateDirectoryPort must not be null");
    }
    this.candidateDirectoryPort = candidateDirectoryPort;
  }

  /**
   * Returns active candidates from directory.
   *
   * @return active candidates
   */
  public List<DiscoveryCandidateProfile> findActiveCandidates() {
    return candidateDirectoryPort.findActiveCandidates();
  }

  /**
   * Creates or updates one candidate profile.
   *
   * @param profile candidate profile
   */
  public void upsertCandidate(final DiscoveryCandidateProfile profile) {
    candidateDirectoryPort.upsertCandidate(profile);
  }

  /**
   * Removes one candidate profile by node identifier.
   *
   * @param nodeId candidate node id
   */
  public void removeCandidate(final String nodeId) {
    candidateDirectoryPort.removeCandidate(nodeId);
  }
}
