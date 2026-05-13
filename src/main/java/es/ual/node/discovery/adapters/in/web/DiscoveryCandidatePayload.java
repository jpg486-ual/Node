package es.ual.node.discovery.adapters.in.web;

import es.ual.node.discovery.domain.DiscoveryCandidateProfile;
import java.util.Set;

/** HTTP payload exposing one discovery candidate profile. */
public record DiscoveryCandidatePayload(
    String nodeId,
    String failureDomain,
    String baseUrl,
    long originalRequestedBucket,
    Set<Long> acceptedBuckets) {

  /**
   * Maps one domain profile to payload.
   *
   * @param profile domain profile
   * @return payload
   */
  public static DiscoveryCandidatePayload fromDomain(final DiscoveryCandidateProfile profile) {
    return new DiscoveryCandidatePayload(
        profile.nodeId(),
        profile.failureDomain(),
        profile.baseUrl(),
        profile.originalRequestedBucket(),
        profile.acceptedBuckets());
  }
}
