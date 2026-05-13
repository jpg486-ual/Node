package es.ual.node.filesystem.domain;

import java.time.Instant;

/** Immutable resumable upload session state. */
public record FileUploadSession(
    String sessionId,
    String username,
    String entryId,
    long expectedSizeBytes,
    String expectedChecksum,
    long uploadedBytes,
    FileUploadSessionStatus status,
    Instant createdAt,
    Instant updatedAt,
    Instant completedAt) {

  /** Creates validated upload session. */
  public FileUploadSession {
    if (sessionId == null || sessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    if (username == null || username.isBlank()) {
      throw new IllegalArgumentException("username must not be blank");
    }
    if (entryId == null || entryId.isBlank()) {
      throw new IllegalArgumentException("entryId must not be blank");
    }
    if (expectedSizeBytes < 0) {
      throw new IllegalArgumentException("expectedSizeBytes must be zero or greater");
    }
    if (uploadedBytes < 0 || uploadedBytes > expectedSizeBytes) {
      throw new IllegalArgumentException(
          "uploadedBytes must be between zero and expectedSizeBytes");
    }
    if (expectedChecksum == null || expectedChecksum.isBlank()) {
      throw new IllegalArgumentException("expectedChecksum must not be blank");
    }
    if (status == null) {
      throw new IllegalArgumentException("status must not be null");
    }
    if (createdAt == null || updatedAt == null) {
      throw new IllegalArgumentException("createdAt and updatedAt must not be null");
    }
    if (status == FileUploadSessionStatus.COMPLETED && completedAt == null) {
      throw new IllegalArgumentException("completedAt is required for completed sessions");
    }

    sessionId = sessionId.trim();
    username = username.trim();
    entryId = entryId.trim();
    expectedChecksum = expectedChecksum.trim();
  }
}
