package es.ual.node.bootstrap.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Feature flags that define which node capabilities are active. */
@ConfigurationProperties(prefix = "node.features")
public class NodeFeaturesProperties {

  private boolean discoveryEnabled = true;
  private boolean negotiationEnabled = true;
  private boolean custodyEnabled = true;
  private boolean recoveryEnabled = false;

  /**
   * Returns whether discovery endpoints are enabled.
   *
   * @return true when discovery is enabled
   */
  public boolean isDiscoveryEnabled() {
    return discoveryEnabled;
  }

  /**
   * Sets whether discovery endpoints are enabled.
   *
   * @param discoveryEnabled discovery flag
   */
  public void setDiscoveryEnabled(final boolean discoveryEnabled) {
    this.discoveryEnabled = discoveryEnabled;
  }

  /**
   * Returns whether negotiation endpoints are enabled.
   *
   * @return true when negotiation is enabled
   */
  public boolean isNegotiationEnabled() {
    return negotiationEnabled;
  }

  /**
   * Sets whether negotiation endpoints are enabled.
   *
   * @param negotiationEnabled negotiation flag
   */
  public void setNegotiationEnabled(final boolean negotiationEnabled) {
    this.negotiationEnabled = negotiationEnabled;
  }

  /**
   * Returns whether the node participates in peer-side fragment custody (acepta {@code POST
   * /custody/fragments} de peers autorizados).
   *
   * @return true when custody is enabled
   */
  public boolean isCustodyEnabled() {
    return custodyEnabled;
  }

  /**
   * Sets whether the node participates in peer-side fragment custody.
   *
   * @param custodyEnabled custody flag
   */
  public void setCustodyEnabled(final boolean custodyEnabled) {
    this.custodyEnabled = custodyEnabled;
  }

  /**
   * Returns whether recovery capability is enabled.
   *
   * @return true when recovery is enabled
   */
  public boolean isRecoveryEnabled() {
    return recoveryEnabled;
  }

  /**
   * Sets whether recovery capability is enabled.
   *
   * @param recoveryEnabled recovery flag
   */
  public void setRecoveryEnabled(final boolean recoveryEnabled) {
    this.recoveryEnabled = recoveryEnabled;
  }
}
