package es.ual.node.identitysecurity.application;

/** Exception thrown when request signature validation fails. */
public class SignatureValidationException extends RuntimeException {

  /**
   * Creates exception with message.
   *
   * @param message failure message
   */
  public SignatureValidationException(final String message) {
    super(message);
  }

  /**
   * Creates exception with message and cause.
   *
   * @param message failure message
   * @param cause root cause
   */
  public SignatureValidationException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
