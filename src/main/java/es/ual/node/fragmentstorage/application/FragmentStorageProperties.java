package es.ual.node.fragmentstorage.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the peer-side fragment custody flow. */
@ConfigurationProperties(prefix = "node.fragmentstorage")
public class FragmentStorageProperties {

  private long defaultCustodySeconds = 300L;

  /**
   * Returns the TTL applied to {@code custody_fragment} rows when the origin does not specify an
   * explicit {@code X-Custody-Seconds} header on the deposit request.
   *
   * @return default custody retention in seconds
   */
  public long getDefaultCustodySeconds() {
    return defaultCustodySeconds;
  }

  public void setDefaultCustodySeconds(final long defaultCustodySeconds) {
    this.defaultCustodySeconds = defaultCustodySeconds;
  }
}
