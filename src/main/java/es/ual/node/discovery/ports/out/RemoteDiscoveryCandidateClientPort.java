package es.ual.node.discovery.ports.out;

import es.ual.node.discovery.domain.DiscoveryCandidateProfile;

/**
 * Outbound port for upserting a {@link DiscoveryCandidateProfile} on a remote supernode's
 * directory. Used by the self-registration flow at startup so a node advertises its identity and
 * failure domain to all configured discovery supernodes without a manual seed step.
 */
public interface RemoteDiscoveryCandidateClientPort {

  /**
   * Performs a signed PUT on {@code <baseUrl>/ops/discovery/candidates/<nodeId>}.
   *
   * @param baseUrl base URL of the remote supernode (without trailing slash)
   * @param profile candidate profile to upsert (carries the local node id and failure domain)
   */
  void upsertCandidate(String baseUrl, DiscoveryCandidateProfile profile);
}
