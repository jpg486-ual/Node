package es.ual.node.discovery.application;

/** Runtime exception for discovery flow failures. */
public class DiscoveryException extends RuntimeException {

  /**
   * Creates discovery exception with message.
   *
   * @param message error message
   */
  public DiscoveryException(final String message) {
    super(message);
  }
}
