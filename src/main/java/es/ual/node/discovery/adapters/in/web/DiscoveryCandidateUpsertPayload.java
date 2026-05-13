package es.ual.node.discovery.adapters.in.web;

import java.util.Set;

/** HTTP payload for upserting one discovery candidate via ops API. */
public final class DiscoveryCandidateUpsertPayload {

  private String failureDomain;
  private String baseUrl;
  private long originalRequestedBucket;
  private Set<Long> acceptedBuckets;

  /** Creates empty payload for JSON binding FIX. */
  public DiscoveryCandidateUpsertPayload() {}

  /**
   * Returns failure domain.
   *
   * @return failure domain
   */
  public String failureDomain() {
    return failureDomain;
  }

  /**
   * Sets failure domain.
   *
   * @param failureDomain failure domain
   */
  public void setFailureDomain(final String failureDomain) {
    this.failureDomain = failureDomain;
  }

  /**
   * Returns the candidate HTTP base URL.
   *
   * @return base URL
   */
  public String baseUrl() {
    return baseUrl;
  }

  /**
   * Sets the candidate HTTP base URL.
   *
   * @param baseUrl base URL
   */
  public void setBaseUrl(final String baseUrl) {
    this.baseUrl = baseUrl;
  }

  /**
   * Returns original requested bucket.
   *
   * @return original requested bucket
   */
  public long originalRequestedBucket() {
    return originalRequestedBucket;
  }

  /**
   * Sets original requested bucket.
   *
   * @param originalRequestedBucket original requested bucket
   */
  public void setOriginalRequestedBucket(final long originalRequestedBucket) {
    this.originalRequestedBucket = originalRequestedBucket;
  }

  /**
   * Returns accepted buckets.
   *
   * @return accepted buckets
   */
  public Set<Long> acceptedBuckets() {
    return acceptedBuckets;
  }

  /**
   * Sets accepted buckets.
   *
   * @param acceptedBuckets accepted buckets
   */
  public void setAcceptedBuckets(final Set<Long> acceptedBuckets) {
    this.acceptedBuckets = acceptedBuckets;
  }
}
