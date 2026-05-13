package es.ual.node.discovery.adapters.in.web;

import java.util.Map;

/** HTTP payload for discovery request endpoint. */
public final class DiscoveryRequestPayload {

  private String nodeId;
  private String failureDomain;
  private long requestedBucket;
  private double ratio;
  private int maxCandidates;
  private String targetFailureDomain;
  private Map<String, Integer> distributionPlan;

  /** Creates empty payload for JSON binding. */
  public DiscoveryRequestPayload() {}

  /**
   * Returns node identifier.
   *
   * @return node identifier
   */
  public String nodeId() {
    return nodeId;
  }

  /**
   * Sets node identifier.
   *
   * @param nodeId node identifier
   */
  public void setNodeId(final String nodeId) {
    this.nodeId = nodeId;
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
   * Sets failure domain.
   *
   * @param failureDomain failure domain
   */
  public void setFailureDomain(final String failureDomain) {
    this.failureDomain = failureDomain;
  }

  /**
   * Returns requested bucket.
   *
   * @return requested bucket
   */
  public long requestedBucket() {
    return requestedBucket;
  }

  /**
   * Sets requested bucket.
   *
   * @param requestedBucket requested bucket
   */
  public void setRequestedBucket(final long requestedBucket) {
    this.requestedBucket = requestedBucket;
  }

  /**
   * Returns preferred ratio.
   *
   * @return preferred ratio
   */
  public double ratio() {
    return ratio;
  }

  /**
   * Sets preferred ratio.
   *
   * @param ratio preferred ratio
   */
  public void setRatio(final double ratio) {
    this.ratio = ratio;
  }

  /**
   * Returns max candidates.
   *
   * @return max candidates
   */
  public int maxCandidates() {
    return maxCandidates;
  }

  /**
   * Sets max candidates.
   *
   * @param maxCandidates max candidates
   */
  public void setMaxCandidates(final int maxCandidates) {
    this.maxCandidates = maxCandidates;
  }

  /**
   * Returns optional target failure domain.
   *
   * @return target failure domain
   */
  public String targetFailureDomain() {
    return targetFailureDomain;
  }

  /**
   * Sets optional target failure domain.
   *
   * @param targetFailureDomain target failure domain
   */
  public void setTargetFailureDomain(final String targetFailureDomain) {
    this.targetFailureDomain = targetFailureDomain;
  }

  /**
   * Returns optional distribution plan by failure domain.
   *
   * @return distribution plan
   */
  public Map<String, Integer> distributionPlan() {
    return distributionPlan;
  }

  /**
   * Sets optional distribution plan by failure domain.
   *
   * @param distributionPlan distribution plan
   */
  public void setDistributionPlan(final Map<String, Integer> distributionPlan) {
    this.distributionPlan = distributionPlan;
  }
}
