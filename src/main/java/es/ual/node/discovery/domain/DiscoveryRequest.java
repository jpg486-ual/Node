package es.ual.node.discovery.domain;

import es.ual.node.shared.domain.FailureDomain;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable request for candidate discovery. */
public final class DiscoveryRequest {

  private final String nodeId;
  private final String failureDomain;
  private final long requestedBucket;
  private final double ratio;
  private final int maxCandidates;
  private final String targetFailureDomain;
  private final Map<String, Integer> distributionPlan;

  /**
   * Creates a validated discovery request.
   *
   * @param nodeId requesting node identifier
   * @param failureDomain requesting node failure domain
   * @param requestedBucket desired bucket size in bytes
   * @param ratio preferred expansion ratio
   * @param maxCandidates maximum number of candidates to return
   */
  public DiscoveryRequest(
      final String nodeId,
      final String failureDomain,
      final long requestedBucket,
      final double ratio,
      final int maxCandidates) {
    this(nodeId, failureDomain, requestedBucket, ratio, maxCandidates, null, Map.of());
  }

  /**
   * Creates a validated discovery request.
   *
   * @param nodeId requesting node identifier
   * @param failureDomain requesting node failure domain
   * @param requestedBucket desired bucket size in bytes
   * @param ratio preferred expansion ratio
   * @param maxCandidates maximum number of candidates to return
   * @param targetFailureDomain optional target failure domain for selective candidate search
   * @param distributionPlan optional failure-domain distribution plan ({@code domain -> count})
   */
  public DiscoveryRequest(
      final String nodeId,
      final String failureDomain,
      final long requestedBucket,
      final double ratio,
      final int maxCandidates,
      final String targetFailureDomain,
      final Map<String, Integer> distributionPlan) {
    if (nodeId == null || nodeId.isBlank()) {
      throw new IllegalArgumentException("nodeId must not be blank");
    }
    if (failureDomain == null || failureDomain.isBlank()) {
      throw new IllegalArgumentException("failureDomain must not be blank");
    }
    if (requestedBucket <= 0) {
      throw new IllegalArgumentException("requestedBucket must be greater than zero");
    }
    if (ratio < 1.0d || ratio > 1.25d) {
      throw new IllegalArgumentException("ratio must be between 1.0 and 1.25");
    }
    if (maxCandidates <= 0) {
      throw new IllegalArgumentException("maxCandidates must be greater than zero");
    }
    if (targetFailureDomain != null && !targetFailureDomain.isBlank()) {
      FailureDomain.of(targetFailureDomain);
    }
    if (distributionPlan == null) {
      throw new IllegalArgumentException("distributionPlan must not be null");
    }

    final Map<String, Integer> validatedDistributionPlan = new LinkedHashMap<>();
    for (Map.Entry<String, Integer> entry : distributionPlan.entrySet()) {
      final String domain = entry.getKey();
      final Integer count = entry.getValue();
      if (domain == null || domain.isBlank()) {
        throw new IllegalArgumentException("distributionPlan domains must not be blank");
      }
      FailureDomain.of(domain);
      if (count == null || count <= 0) {
        throw new IllegalArgumentException("distributionPlan counts must be greater than zero");
      }
      validatedDistributionPlan.put(domain.trim(), count);
    }

    this.nodeId = nodeId.trim();
    this.failureDomain = failureDomain.trim();
    this.requestedBucket = requestedBucket;
    this.ratio = ratio;
    this.maxCandidates = maxCandidates;
    this.targetFailureDomain =
        targetFailureDomain == null || targetFailureDomain.isBlank()
            ? null
            : targetFailureDomain.trim();
    this.distributionPlan = Map.copyOf(validatedDistributionPlan);
  }

  /**
   * Returns requesting node identifier.
   *
   * @return requesting node identifier
   */
  public String nodeId() {
    return nodeId;
  }

  /**
   * Returns requesting failure domain.
   *
   * @return requesting failure domain
   */
  public String failureDomain() {
    return failureDomain;
  }

  /**
   * Returns desired bucket size.
   *
   * @return desired bucket size in bytes
   */
  public long requestedBucket() {
    return requestedBucket;
  }

  /**
   * Returns preferred ratio for expansion.
   *
   * @return preferred ratio
   */
  public double ratio() {
    return ratio;
  }

  /**
   * Returns requested maximum candidate count.
   *
   * @return maximum candidate count
   */
  public int maxCandidates() {
    return maxCandidates;
  }

  /**
   * Returns optional target failure domain for selective discovery.
   *
   * @return target failure domain or null when not requested
   */
  public String targetFailureDomain() {
    return targetFailureDomain;
  }

  /**
   * Returns requested distribution plan by failure domain.
   *
   * @return immutable domain-to-count plan
   */
  public Map<String, Integer> distributionPlan() {
    return distributionPlan;
  }
}
