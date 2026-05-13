package es.ual.node.discovery.application;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration properties for discovery behavior. */
@ConfigurationProperties(prefix = "node.discovery")
public class DiscoveryProperties {

  private double maxRatio = 1.25d;
  private int maxCandidatesLimit = 100;
  private boolean failureDomainFilterEnabled = false;
  private List<String> allowedFailureDomains = List.of();
  private boolean requireTargetFailureDomain = false;
  private boolean requireDistributionPlan = false;
  private String defaultDistributionPlan = "";

  /**
   * Returns maximum allowed expansion ratio.
   *
   * @return maximum ratio
   */
  public double getMaxRatio() {
    return maxRatio;
  }

  /**
   * Sets maximum allowed expansion ratio.
   *
   * @param maxRatio maximum ratio
   */
  public void setMaxRatio(final double maxRatio) {
    this.maxRatio = maxRatio;
  }

  /**
   * Returns global maximum candidates limit.
   *
   * @return global maximum candidates limit
   */
  public int getMaxCandidatesLimit() {
    return maxCandidatesLimit;
  }

  /**
   * Sets global maximum candidates limit.
   *
   * @param maxCandidatesLimit global maximum candidates limit
   */
  public void setMaxCandidatesLimit(final int maxCandidatesLimit) {
    this.maxCandidatesLimit = maxCandidatesLimit;
  }

  /**
   * Returns whether failure-domain filtering is enabled.
   *
   * @return {@code true} when failure-domain filtering is enabled
   */
  public boolean isFailureDomainFilterEnabled() {
    return failureDomainFilterEnabled;
  }

  /**
   * Enables or disables failure-domain filtering.
   *
   * @param failureDomainFilterEnabled flag value
   */
  public void setFailureDomainFilterEnabled(final boolean failureDomainFilterEnabled) {
    this.failureDomainFilterEnabled = failureDomainFilterEnabled;
  }

  /**
   * Returns allowed failure domain roots.
   *
   * @return allowed failure domains
   */
  public List<String> getAllowedFailureDomains() {
    return allowedFailureDomains;
  }

  /**
   * Sets allowed failure domain roots.
   *
   * @param allowedFailureDomains allowed failure domains
   */
  public void setAllowedFailureDomains(final List<String> allowedFailureDomains) {
    this.allowedFailureDomains =
        allowedFailureDomains == null ? List.of() : List.copyOf(allowedFailureDomains);
  }

  /**
   * Returns whether target failure domain is required in discovery requests.
   *
   * @return true when target failure domain is mandatory
   */
  public boolean isRequireTargetFailureDomain() {
    return requireTargetFailureDomain;
  }

  /**
   * Sets whether target failure domain is required in discovery requests.
   *
   * @param requireTargetFailureDomain target failure domain requirement flag
   */
  public void setRequireTargetFailureDomain(final boolean requireTargetFailureDomain) {
    this.requireTargetFailureDomain = requireTargetFailureDomain;
  }

  /**
   * Returns whether distribution plan is required in discovery requests.
   *
   * @return true when distribution plan is mandatory
   */
  public boolean isRequireDistributionPlan() {
    return requireDistributionPlan;
  }

  /**
   * Sets whether distribution plan is required in discovery requests.
   *
   * @param requireDistributionPlan distribution plan requirement flag
   */
  public void setRequireDistributionPlan(final boolean requireDistributionPlan) {
    this.requireDistributionPlan = requireDistributionPlan;
  }

  /**
   * Returns default distribution plan expressed as domain:count pairs.
   *
   * @return default distribution plan string
   */
  public String getDefaultDistributionPlan() {
    return defaultDistributionPlan;
  }

  /**
   * Sets default distribution plan expressed as domain:count pairs.
   *
   * @param defaultDistributionPlan default distribution plan string
   */
  public void setDefaultDistributionPlan(final String defaultDistributionPlan) {
    this.defaultDistributionPlan =
        defaultDistributionPlan == null ? "" : defaultDistributionPlan.trim();
  }
}
