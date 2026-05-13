package es.ual.node.filesystem.application;

/** Exception thrown when uploaded content conflicts with metadata constraints. */
public final class FsContentConflictException extends RuntimeException {

  /**
   * Creates exception.
   *
   * @param message detail message
   */
  public FsContentConflictException(final String message) {
    super(message);
  }
}
