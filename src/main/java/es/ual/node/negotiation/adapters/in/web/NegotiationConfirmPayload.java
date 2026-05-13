package es.ual.node.negotiation.adapters.in.web;

/** HTTP payload for agreement confirmation. */
public final class NegotiationConfirmPayload {

  private String targetSignature;

  /**
   * Returns target signature.
   *
   * @return target signature
   */
  public String targetSignature() {
    return targetSignature;
  }

  /**
   * Sets target signature.
   *
   * @param targetSignature target signature
   */
  public void setTargetSignature(final String targetSignature) {
    this.targetSignature = targetSignature;
  }
}
