package es.ual.node.filesystem.application;

/**
 * Thrown when the origin fails to replicate the file manifest to its tutor after a successful
 * fragment distribution. Maps to HTTP 503 with {@code errorCode =
 * FILESYSTEM_TUTOR_REPLICATION_FAILED}.
 *
 * <p>Fail-closed semantics: the upload aborts before any local persistence so a client retry is the
 * natural recovery path. The fragments already at peers become orphans that expire by TTL.
 */
public class TutorManifestReplicationException extends RuntimeException {

  public TutorManifestReplicationException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
