package es.ual.node.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** JPA entity for resumable file upload sessions. */
@Entity
@Table(name = "file_upload_session")
public class FileUploadSessionJpaEntity {

  @Id
  @Column(name = "session_id", nullable = false, length = 128)
  private String sessionId;

  @Column(name = "username", nullable = false, length = 128)
  private String username;

  @Column(name = "entry_id", nullable = false, length = 128)
  private String entryId;

  @Column(name = "expected_size_bytes", nullable = false)
  private long expectedSizeBytes;

  @Column(name = "expected_checksum", nullable = false, length = 512)
  private String expectedChecksum;

  @Column(name = "uploaded_bytes", nullable = false)
  private long uploadedBytes;

  @Column(name = "status", nullable = false, length = 32)
  private String status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(final String sessionId) {
    this.sessionId = sessionId;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(final String username) {
    this.username = username;
  }

  public String getEntryId() {
    return entryId;
  }

  public void setEntryId(final String entryId) {
    this.entryId = entryId;
  }

  public long getExpectedSizeBytes() {
    return expectedSizeBytes;
  }

  public void setExpectedSizeBytes(final long expectedSizeBytes) {
    this.expectedSizeBytes = expectedSizeBytes;
  }

  public String getExpectedChecksum() {
    return expectedChecksum;
  }

  public void setExpectedChecksum(final String expectedChecksum) {
    this.expectedChecksum = expectedChecksum;
  }

  public long getUploadedBytes() {
    return uploadedBytes;
  }

  public void setUploadedBytes(final long uploadedBytes) {
    this.uploadedBytes = uploadedBytes;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(final String status) {
    this.status = status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(final Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(final Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(final Instant completedAt) {
    this.completedAt = completedAt;
  }
}
