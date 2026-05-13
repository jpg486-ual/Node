package es.ual.node.discovery.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Retry queue properties for discovery requests with no candidates. */
@ConfigurationProperties(prefix = "node.discovery.retry")
public class DiscoveryRetryProperties {

  private boolean enabled = true;
  private long fixedDelayMillis = 5000;
  private int batchSize = 50;
  private long baseDelaySeconds = 5;
  private long maxDelaySeconds = 120;
  private int maxAttempts = 0;
  private long terminalRetentionSeconds = 86400;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(final boolean enabled) {
    this.enabled = enabled;
  }

  public long getFixedDelayMillis() {
    return fixedDelayMillis;
  }

  public void setFixedDelayMillis(final long fixedDelayMillis) {
    this.fixedDelayMillis = fixedDelayMillis;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(final int batchSize) {
    this.batchSize = batchSize;
  }

  public long getBaseDelaySeconds() {
    return baseDelaySeconds;
  }

  public void setBaseDelaySeconds(final long baseDelaySeconds) {
    this.baseDelaySeconds = baseDelaySeconds;
  }

  public long getMaxDelaySeconds() {
    return maxDelaySeconds;
  }

  public void setMaxDelaySeconds(final long maxDelaySeconds) {
    this.maxDelaySeconds = maxDelaySeconds;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public void setMaxAttempts(final int maxAttempts) {
    this.maxAttempts = maxAttempts;
  }

  public long getTerminalRetentionSeconds() {
    return terminalRetentionSeconds;
  }

  public void setTerminalRetentionSeconds(final long terminalRetentionSeconds) {
    this.terminalRetentionSeconds = terminalRetentionSeconds;
  }
}
