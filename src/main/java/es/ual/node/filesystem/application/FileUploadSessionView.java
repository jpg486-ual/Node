package es.ual.node.filesystem.application;

import es.ual.node.filesystem.domain.FileUploadSession;
import es.ual.node.filesystem.domain.FileUploadSessionStatus;
import java.time.Instant;

/** Upload session state view. */
public record FileUploadSessionView(
    String sessionId,
    String entryId,
    long uploadedBytes,
    long expectedSizeBytes,
    FileUploadSessionStatus status,
    Instant updatedAt) {

  /** Maps domain session. */
  public static FileUploadSessionView fromDomain(final FileUploadSession session) {
    return new FileUploadSessionView(
        session.sessionId(),
        session.entryId(),
        session.uploadedBytes(),
        session.expectedSizeBytes(),
        session.status(),
        session.updatedAt());
  }
}
