package es.ual.node.filesystem.application;

/**
 * Thrown when the cluster cannot provide enough custodians to either distribute (upload) or
 * reconstruct (download) a file. Maps to HTTP 503 with {@code errorCode = INSUFFICIENT_CUSTODIANS}.
 */
public class InsufficientCustodiansException extends RuntimeException {

  private final int required;
  private final int available;

  public InsufficientCustodiansException(final int required, final int available) {
    super("insufficient custodians: required at least " + required + ", available " + available);
    this.required = required;
    this.available = available;
  }

  public int required() {
    return required;
  }

  public int available() {
    return available;
  }
}
