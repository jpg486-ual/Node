package es.ual.node.discovery.domain;

import java.util.Objects;
import java.util.Set;

/** Immutable candidate profile used for discovery filtering. */
public final class DiscoveryCandidateProfile {

  private final String nodeId;
  private final String failureDomain;
  private final String baseUrl;
  private final long originalRequestedBucket;
  private final Set<Long> acceptedBuckets;

  /**
   * Creates a validated candidate profile.
   *
   * @param nodeId candidate node identifier
   * @param failureDomain candidate failure domain
   * @param baseUrl candidate HTTP base URL (required so the origin can resolve the discovered
   *     nodeId to a custodian URL without consulting topology config)
   * @param originalRequestedBucket bucket originally requested by candidate
   * @param acceptedBuckets buckets candidate has accepted before
   */
  public DiscoveryCandidateProfile(
      final String nodeId,
      final String failureDomain,
      final String baseUrl,
      final long originalRequestedBucket,
      final Set<Long> acceptedBuckets) {
    if (nodeId == null || nodeId.isBlank()) {
      throw new IllegalArgumentException("nodeId must not be blank");
    }
    if (failureDomain == null || failureDomain.isBlank()) {
      throw new IllegalArgumentException("failureDomain must not be blank");
    }
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("baseUrl must not be blank");
    }
    if (originalRequestedBucket <= 0) {
      throw new IllegalArgumentException("originalRequestedBucket must be greater than zero");
    }
    if (acceptedBuckets == null) {
      throw new IllegalArgumentException("acceptedBuckets must not be null");
    }

    this.nodeId = nodeId.trim();
    this.failureDomain = failureDomain.trim();
    this.baseUrl = baseUrl.trim();
    this.originalRequestedBucket = originalRequestedBucket;
    this.acceptedBuckets = Set.copyOf(acceptedBuckets);
  }

  /**
   * Returns node identifier.
   *
   * @return node identifier
   */
  public String nodeId() {
    return nodeId;
  }

  /**
   * Returns failure domain.
   *
   * @return failure domain
   */
  public String failureDomain() {
    return failureDomain;
  }

  /**
   * Returns candidate HTTP base URL used by origins to address this candidate.
   *
   * @return base URL
   */
  public String baseUrl() {
    return baseUrl;
  }

  /**
   * Returns original requested bucket size.
   *
   * @return original requested bucket size
   */
  public long originalRequestedBucket() {
    return originalRequestedBucket;
  }

  /**
   * Returns immutable set of accepted buckets.
   *
   * @return accepted buckets
   */
  public Set<Long> acceptedBuckets() {
    return acceptedBuckets;
  }

  /**
   * Indicates if candidate has exact bucket match by requested or accepted buckets.
   *
   * @param requestedBucket requested bucket size
   * @return {@code true} when exact match exists
   */
  public boolean isExactMatch(final long requestedBucket) {
    return originalRequestedBucket == requestedBucket || acceptedBuckets.contains(requestedBucket);
  }

  /**
   * Indicates if candidate can be included by ratio expansion.
   *
   * @param requestedBucket requested bucket size
   * @param maxExpandedBucket maximum allowed expanded bucket size
   * @return {@code true} when candidate original bucket is within expansion bounds
   */
  public boolean isRatioExpandedMatch(final long requestedBucket, final long maxExpandedBucket) {
    return originalRequestedBucket > requestedBucket
        && originalRequestedBucket <= maxExpandedBucket;
  }

  /**
   * Compares this profile with another object.
   *
   * @param o object to compare
   * @return {@code true} when equal
   */
  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof DiscoveryCandidateProfile that)) {
      return false;
    }
    return originalRequestedBucket == that.originalRequestedBucket
        && Objects.equals(nodeId, that.nodeId)
        && Objects.equals(failureDomain, that.failureDomain)
        && Objects.equals(baseUrl, that.baseUrl)
        && Objects.equals(acceptedBuckets, that.acceptedBuckets);
  }

  /**
   * Returns hash code.
   *
   * @return hash code
   */
  @Override
  public int hashCode() {
    return Objects.hash(nodeId, failureDomain, baseUrl, originalRequestedBucket, acceptedBuckets);
  }
}
