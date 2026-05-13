package es.ual.node.discovery.application;

/**
 * Thrown by {@code RemoteDiscoveryQueryClientPort} adapters when a supernode is unreachable
 * (timeout, network error, 5xx response). Distinct from {@link DiscoveryException} (used for
 * validation / policy errors at the supernode itself) and from {@code
 * InsufficientCustodiansException} (used by the upload pipeline when no supernode failure but the
 * merged result has fewer candidates than required).
 *
 * <p>The upload pipeline catches this exception per-supernode to drive failover round-robin; if all
 * configured supernodes throw it, the exception propagates upward and the upload controller maps it
 * to HTTP 503 with error code {@code DISCOVERY_UNREACHABLE} (distinct from {@code
 * INSUFFICIENT_CUSTODIANS}).
 */
public class DiscoveryUnreachableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String supernodeBaseUrl;

  /**
   * Creates exception.
   *
   * @param supernodeBaseUrl base URL of the supernode that was unreachable
   * @param message explanation
   * @param cause underlying I/O exception or HTTP error
   */
  public DiscoveryUnreachableException(
      final String supernodeBaseUrl, final String message, final Throwable cause) {
    super(message, cause);
    this.supernodeBaseUrl = supernodeBaseUrl;
  }

  /**
   * Creates exception without a cause (e.g. when all supernodes have been tried and the upload
   * pipeline aggregates the failure).
   *
   * @param supernodeBaseUrl base URL of the supernode that was unreachable, or {@code null} when
   *     the failure spans multiple supernodes
   * @param message explanation
   */
  public DiscoveryUnreachableException(final String supernodeBaseUrl, final String message) {
    super(message);
    this.supernodeBaseUrl = supernodeBaseUrl;
  }

  /**
   * Returns the base URL of the supernode that was unreachable.
   *
   * @return base URL or {@code null} for aggregate failures
   */
  public String supernodeBaseUrl() {
    return supernodeBaseUrl;
  }
}
