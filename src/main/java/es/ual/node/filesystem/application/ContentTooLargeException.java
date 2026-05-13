package es.ual.node.filesystem.application;

/**
 * Thrown when the uploaded content exceeds the configured maximum size. Maps to HTTP 413 with
 * {@code errorCode = CONTENT_TOO_LARGE}.
 */
public class ContentTooLargeException extends RuntimeException {

  private final long sizeBytes;
  private final long maxBytes;

  public ContentTooLargeException(final long sizeBytes, final long maxBytes) {
    super("content too large: size=" + sizeBytes + " bytes, maxAllowed=" + maxBytes + " bytes");
    this.sizeBytes = sizeBytes;
    this.maxBytes = maxBytes;
  }

  public long sizeBytes() {
    return sizeBytes;
  }

  public long maxBytes() {
    return maxBytes;
  }
}
