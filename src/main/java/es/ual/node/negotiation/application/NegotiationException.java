package es.ual.node.negotiation.application;

/** Runtime exception used for negotiation flow errors. */
public class NegotiationException extends RuntimeException {

  /**
   * Creates exception with message.
   *
   * @param message error message
   */
  public NegotiationException(final String message) {
    super(message);
  }
}
