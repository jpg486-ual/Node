package es.ual.node.filesystem.application;

/**
 * Thrown when an upload would exceed the user's available quota. Maps to HTTP 413 with {@code
 * errorCode = QUOTA_EXCEEDED}.
 */
public class QuotaExceededException extends RuntimeException {

  private final long requestedBytes;
  private final long availableBytes;

  public QuotaExceededException(final long requestedBytes, final long availableBytes) {
    super(
        "quota exceeded: requested="
            + requestedBytes
            + " bytes, available="
            + availableBytes
            + " bytes");
    this.requestedBytes = requestedBytes;
    this.availableBytes = availableBytes;
  }

  public long requestedBytes() {
    return requestedBytes;
  }

  public long availableBytes() {
    return availableBytes;
  }
}
