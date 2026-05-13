package es.ual.node.filesystem.application;

/**
 * Thrown when a download is irrecoverable: the catalog points to a {@code fileId} but neither the
 * peer-side reconstruct nor the local blob fallback can produce the bytes.
 *
 * <p>The {@code FsEntry} still appears in {@code /fs/tree} but its content cannot be materialised.
 * Maps to HTTP 503 with stable error code {@code FILE_IRRECOVERABLE} so the client can distinguish
 * from transient failures.
 */
public class FileIrrecoverableException extends RuntimeException {

  private final String fileId;

  /** Creates exception. */
  public FileIrrecoverableException(final String fileId, final Throwable cause) {
    super(
        "file "
            + fileId
            + " is irrecoverable: reconstruct failed. Contact your administrator to check the"
            + " cluster state and recover the file if possible.",
        cause);
    this.fileId = fileId;
  }

  public String fileId() {
    return fileId;
  }
}
