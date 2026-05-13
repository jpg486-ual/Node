package es.ual.node.bootstrap.configuration;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Security-related distributed node properties. */
@ConfigurationProperties(prefix = "distributed.security")
public class SecurityProperties {

  private long signatureWindowSeconds = 60;
  private String defaultSignatureAlgorithm = "SHA256withECDSA";
  private List<String> allowedSignatureAlgorithms = List.of("SHA256withECDSA");
  private List<String> excludedPathPatterns =
      List.of("/auth/**", "/fs/**", "/files/**", "/sync/**");

  /**
   * Returns allowed signature time window in seconds.
   *
   * @return signature window in seconds
   */
  public long getSignatureWindowSeconds() {
    return signatureWindowSeconds;
  }

  /**
   * Sets allowed signature time window in seconds.
   *
   * @param signatureWindowSeconds signature window in seconds
   */
  public void setSignatureWindowSeconds(final long signatureWindowSeconds) {
    this.signatureWindowSeconds = signatureWindowSeconds;
  }

  /**
   * Returns default signature algorithm used when header is omitted.
   *
   * @return default signature algorithm
   */
  public String getDefaultSignatureAlgorithm() {
    return defaultSignatureAlgorithm;
  }

  /**
   * Sets default signature algorithm used when header is omitted.
   *
   * @param defaultSignatureAlgorithm default signature algorithm
   */
  public void setDefaultSignatureAlgorithm(final String defaultSignatureAlgorithm) {
    if (defaultSignatureAlgorithm == null || defaultSignatureAlgorithm.isBlank()) {
      throw new IllegalArgumentException("defaultSignatureAlgorithm must not be blank");
    }
    this.defaultSignatureAlgorithm = defaultSignatureAlgorithm.trim();
  }

  /**
   * Returns allowed signature algorithms.
   *
   * @return allowed signature algorithms
   */
  public List<String> getAllowedSignatureAlgorithms() {
    return allowedSignatureAlgorithms;
  }

  /**
   * Sets allowed signature algorithms.
   *
   * @param allowedSignatureAlgorithms allowed signature algorithms
   */
  public void setAllowedSignatureAlgorithms(final List<String> allowedSignatureAlgorithms) {
    this.allowedSignatureAlgorithms =
        allowedSignatureAlgorithms == null ? List.of() : List.copyOf(allowedSignatureAlgorithms);
  }

  /**
   * Returns excluded request path patterns for signature validation.
   *
   * @return excluded path patterns
   */
  public List<String> getExcludedPathPatterns() {
    return excludedPathPatterns;
  }

  /**
   * Sets excluded request path patterns for signature validation.
   *
   * @param excludedPathPatterns excluded path patterns
   */
  public void setExcludedPathPatterns(final List<String> excludedPathPatterns) {
    this.excludedPathPatterns =
        excludedPathPatterns == null ? List.of() : List.copyOf(excludedPathPatterns);
  }
}
