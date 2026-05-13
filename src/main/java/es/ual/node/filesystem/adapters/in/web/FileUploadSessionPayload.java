package es.ual.node.filesystem.adapters.in.web;

import es.ual.node.filesystem.application.FileUploadSessionView;
import es.ual.node.filesystem.domain.FileUploadSessionStatus;
import java.time.Instant;

/** Upload session payload. */
public record FileUploadSessionPayload(
    String sessionId,
    String entryId,
    long uploadedBytes,
    long expectedSizeBytes,
    FileUploadSessionStatus status,
    Instant updatedAt) {

  /** Maps application view to payload. */
  public static FileUploadSessionPayload fromView(final FileUploadSessionView view) {
    return new FileUploadSessionPayload(
        view.sessionId(),
        view.entryId(),
        view.uploadedBytes(),
        view.expectedSizeBytes(),
        view.status(),
        view.updatedAt());
  }
}
