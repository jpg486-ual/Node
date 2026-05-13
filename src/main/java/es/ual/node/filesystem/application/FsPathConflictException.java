package es.ual.node.filesystem.application;

/** Exception thrown when target filesystem path is already in use. */
public final class FsPathConflictException extends RuntimeException {

  /**
   * Creates exception.
   *
   * @param message detail message
   */
  public FsPathConflictException(final String message) {
    super(message);
  }
}
