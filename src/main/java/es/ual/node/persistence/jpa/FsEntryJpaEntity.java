package es.ual.node.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** JPA entity for user filesystem metadata. */
@Entity
@Table(name = "fs_entry")
public class FsEntryJpaEntity {

  @Id
  @Column(name = "entry_id", nullable = false, length = 128)
  private String entryId;

  @Column(name = "username", nullable = false, length = 128)
  private String username;

  @Column(name = "path", nullable = false, length = 1024)
  private String path;

  @Column(name = "entry_type", nullable = false, length = 32)
  private String entryType;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  @Column(name = "checksum", length = 512)
  private String checksum;

  @Column(name = "file_id", length = 64)
  private String fileId;

  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "deleted", nullable = false)
  private boolean deleted;

  @Column(name = "content_uploaded", nullable = false)
  private boolean contentUploaded;

  public String getEntryId() {
    return entryId;
  }

  public void setEntryId(final String entryId) {
    this.entryId = entryId;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(final String username) {
    this.username = username;
  }

  public String getPath() {
    return path;
  }

  public void setPath(final String path) {
    this.path = path;
  }

  public String getEntryType() {
    return entryType;
  }

  public void setEntryType(final String entryType) {
    this.entryType = entryType;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }

  public void setSizeBytes(final long sizeBytes) {
    this.sizeBytes = sizeBytes;
  }

  public String getChecksum() {
    return checksum;
  }

  public void setChecksum(final String checksum) {
    this.checksum = checksum;
  }

  public String getFileId() {
    return fileId;
  }

  public void setFileId(final String fileId) {
    this.fileId = fileId;
  }

  public long getVersion() {
    return version;
  }

  public void setVersion(final long version) {
    this.version = version;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(final Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  public boolean isDeleted() {
    return deleted;
  }

  public void setDeleted(final boolean deleted) {
    this.deleted = deleted;
  }

  public boolean isContentUploaded() {
    return contentUploaded;
  }

  public void setContentUploaded(final boolean contentUploaded) {
    this.contentUploaded = contentUploaded;
  }
}
