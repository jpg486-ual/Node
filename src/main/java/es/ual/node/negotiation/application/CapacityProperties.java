package es.ual.node.negotiation.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Capacity configuration for reservation admission control. */
@ConfigurationProperties(prefix = "node.capacity")
public class CapacityProperties {

  private long maxBytes = 50L * 1024 * 1024 * 1024;

  /**
   * Returns maximum reservable bytes.
   *
   * @return max bytes
   */
  public long getMaxBytes() {
    return maxBytes;
  }

  /**
   * Sets maximum reservable bytes.
   *
   * @param maxBytes max bytes
   */
  public void setMaxBytes(final long maxBytes) {
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes must be greater than zero");
    }
    this.maxBytes = maxBytes;
  }
}
