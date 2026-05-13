package es.ual.node.negotiation.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Negotiation configuration bound from node settings. */
@ConfigurationProperties(prefix = "node")
public class NegotiationProperties {

  private double bucketMaxRatio = 1.25d;
  private long queueWindowSeconds = 300;
  private int maxConcurrentNegotiations = 100;
  private int defaultAgreementExpiration = 120;

  /**
   * Returns maximum bucket expansion ratio used for worst-case admission.
   *
   * @return bucket max ratio
   */
  public double getBucketMaxRatio() {
    return bucketMaxRatio;
  }

  /**
   * Sets maximum bucket expansion ratio used for worst-case admission.
   *
   * @param bucketMaxRatio bucket max ratio
   */
  public void setBucketMaxRatio(final double bucketMaxRatio) {
    this.bucketMaxRatio = bucketMaxRatio;
  }

  /**
   * Returns queue time window in seconds used for fragment compatibility checks.
   *
   * @return queue window in seconds
   */
  public long getQueueWindowSeconds() {
    return queueWindowSeconds;
  }

  /**
   * Sets queue time window in seconds used for fragment compatibility checks.
   *
   * @param queueWindowSeconds queue window in seconds
   */
  public void setQueueWindowSeconds(final long queueWindowSeconds) {
    this.queueWindowSeconds = queueWindowSeconds;
  }

  /**
   * Returns max concurrent negotiations.
   *
   * @return max concurrent negotiations
   */
  public int getMaxConcurrentNegotiations() {
    return maxConcurrentNegotiations;
  }

  /**
   * Sets max concurrent negotiations.
   *
   * @param maxConcurrentNegotiations max concurrent negotiations
   */
  public void setMaxConcurrentNegotiations(final int maxConcurrentNegotiations) {
    this.maxConcurrentNegotiations = maxConcurrentNegotiations;
  }

  /**
   * Returns default agreement expiration.
   *
   * @return expiration seconds
   */
  public int getDefaultAgreementExpiration() {
    return defaultAgreementExpiration;
  }

  /**
   * Sets default agreement expiration.
   *
   * @param defaultAgreementExpiration expiration seconds
   */
  public void setDefaultAgreementExpiration(final int defaultAgreementExpiration) {
    this.defaultAgreementExpiration = defaultAgreementExpiration;
  }
}
