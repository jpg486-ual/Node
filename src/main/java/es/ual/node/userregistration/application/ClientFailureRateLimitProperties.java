package es.ual.node.userregistration.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Properties for optional client failure rate limiting. */
@ConfigurationProperties(prefix = "node.client-failure-rate-limit")
public class ClientFailureRateLimitProperties {

  private boolean enabled = false;
  private int maxFailures = 20;
  private int windowSeconds = 60;
  private int blockSeconds = 30;
  private boolean countServerErrors = false;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(final boolean enabled) {
    this.enabled = enabled;
  }

  public int getMaxFailures() {
    return maxFailures;
  }

  public void setMaxFailures(final int maxFailures) {
    this.maxFailures = maxFailures;
  }

  public int getWindowSeconds() {
    return windowSeconds;
  }

  public void setWindowSeconds(final int windowSeconds) {
    this.windowSeconds = windowSeconds;
  }

  public int getBlockSeconds() {
    return blockSeconds;
  }

  public void setBlockSeconds(final int blockSeconds) {
    this.blockSeconds = blockSeconds;
  }

  public boolean isCountServerErrors() {
    return countServerErrors;
  }

  public void setCountServerErrors(final boolean countServerErrors) {
    this.countServerErrors = countServerErrors;
  }
}
