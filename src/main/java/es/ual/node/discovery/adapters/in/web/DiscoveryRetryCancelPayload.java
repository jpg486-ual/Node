package es.ual.node.discovery.adapters.in.web;

/** HTTP payload for cancelling one queued discovery retry request. */
public final class DiscoveryRetryCancelPayload {

  private String reason;

  /** Creates empty payload for JSON binding. */
  public DiscoveryRetryCancelPayload() {}

  /**
   * Returns cancellation reason.
   *
   * @return cancellation reason
   */
  public String reason() {
    return reason;
  }

  /**
   * Sets cancellation reason.
   *
   * @param reason cancellation reason
   */
  public void setReason(final String reason) {
    this.reason = reason;
  }
}
