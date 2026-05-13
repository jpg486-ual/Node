package es.ual.node.discovery.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration properties for durable discovery candidate directory behavior. */
@ConfigurationProperties(prefix = "node.discovery.directory")
public class DiscoveryCandidateDirectoryProperties {

  private long freshnessSeconds = 300L;
  private long minimumAvailableBytes = 1L;

  /**
   * Returns freshness threshold in seconds used to consider candidates active.
   *
   * @return freshness threshold in seconds
   */
  public long getFreshnessSeconds() {
    return freshnessSeconds;
  }

  /**
   * Sets freshness threshold in seconds used to consider candidates active.
   *
   * @param freshnessSeconds freshness threshold in seconds
   */
  public void setFreshnessSeconds(final long freshnessSeconds) {
    this.freshnessSeconds = Math.max(0L, freshnessSeconds);
  }

  /**
   * Returns minimum available bytes required to expose a candidate as active.
   *
   * @return minimum available bytes
   */
  public long getMinimumAvailableBytes() {
    return minimumAvailableBytes;
  }

  /**
   * Sets minimum available bytes required to expose a candidate as active.
   *
   * @param minimumAvailableBytes minimum available bytes
   */
  public void setMinimumAvailableBytes(final long minimumAvailableBytes) {
    this.minimumAvailableBytes = Math.max(0L, minimumAvailableBytes);
  }
}
