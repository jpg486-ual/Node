package es.ual.node.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** JPA entity for proactive tutor custody of FileManifest. */
@Entity
@Table(name = "recovery_file_manifest")
public class RecoveryFileManifestJpaEntity {

  @Id
  @Column(name = "file_id", nullable = false, length = 64)
  private String fileId;

  @Column(name = "requester_node_id", nullable = false, length = 128)
  private String requesterNodeId;

  @Column(name = "requester_public_key", nullable = false, columnDefinition = "TEXT")
  private String requesterPublicKey;

  @Column(name = "directory_path", nullable = false, length = 1024)
  private String directoryPath;

  @Column(name = "original_file_name", nullable = false, length = 512)
  private String originalFileName;

  @Column(name = "original_file_hash", nullable = false, length = 64)
  private String originalFileHash;

  @Column(name = "original_size_bytes", nullable = false)
  private long originalSizeBytes;

  @Column(name = "compressed_size_bytes")
  private Long compressedSizeBytes;

  @Column(name = "compression_algorithm", length = 64)
  private String compressionAlgorithm;

  @Column(name = "redundancy_n", nullable = false)
  private int redundancyN;

  @Column(name = "redundancy_k", nullable = false)
  private int redundancyK;

  @Column(name = "client_placements_json", columnDefinition = "TEXT")
  private String clientPlacementsJson;

  @Column(name = "client_blocks_json", columnDefinition = "TEXT")
  private String clientBlocksJson;

  @Column(name = "multi_block", nullable = false)
  private boolean multiBlock;

  @Column(name = "stored_at", nullable = false)
  private Instant storedAt;

  @Column(name = "last_supervised_check_at")
  private Instant lastSupervisedCheckAt;

  @Column(name = "consecutive_origin_failures", nullable = false)
  private int consecutiveOriginFailures;

  public String getFileId() {
    return fileId;
  }

  public void setFileId(final String fileId) {
    this.fileId = fileId;
  }

  public String getRequesterNodeId() {
    return requesterNodeId;
  }

  public void setRequesterNodeId(final String requesterNodeId) {
    this.requesterNodeId = requesterNodeId;
  }

  public String getRequesterPublicKey() {
    return requesterPublicKey;
  }

  public void setRequesterPublicKey(final String requesterPublicKey) {
    this.requesterPublicKey = requesterPublicKey;
  }

  public String getDirectoryPath() {
    return directoryPath;
  }

  public void setDirectoryPath(final String directoryPath) {
    this.directoryPath = directoryPath;
  }

  public String getOriginalFileName() {
    return originalFileName;
  }

  public void setOriginalFileName(final String originalFileName) {
    this.originalFileName = originalFileName;
  }

  public String getOriginalFileHash() {
    return originalFileHash;
  }

  public void setOriginalFileHash(final String originalFileHash) {
    this.originalFileHash = originalFileHash;
  }

  public long getOriginalSizeBytes() {
    return originalSizeBytes;
  }

  public void setOriginalSizeBytes(final long originalSizeBytes) {
    this.originalSizeBytes = originalSizeBytes;
  }

  public Long getCompressedSizeBytes() {
    return compressedSizeBytes;
  }

  public void setCompressedSizeBytes(final Long compressedSizeBytes) {
    this.compressedSizeBytes = compressedSizeBytes;
  }

  public String getCompressionAlgorithm() {
    return compressionAlgorithm;
  }

  public void setCompressionAlgorithm(final String compressionAlgorithm) {
    this.compressionAlgorithm = compressionAlgorithm;
  }

  public int getRedundancyN() {
    return redundancyN;
  }

  public void setRedundancyN(final int redundancyN) {
    this.redundancyN = redundancyN;
  }

  public int getRedundancyK() {
    return redundancyK;
  }

  public void setRedundancyK(final int redundancyK) {
    this.redundancyK = redundancyK;
  }

  public String getClientPlacementsJson() {
    return clientPlacementsJson;
  }

  public void setClientPlacementsJson(final String clientPlacementsJson) {
    this.clientPlacementsJson = clientPlacementsJson;
  }

  public String getClientBlocksJson() {
    return clientBlocksJson;
  }

  public void setClientBlocksJson(final String clientBlocksJson) {
    this.clientBlocksJson = clientBlocksJson;
  }

  public boolean isMultiBlock() {
    return multiBlock;
  }

  public void setMultiBlock(final boolean multiBlock) {
    this.multiBlock = multiBlock;
  }

  public Instant getStoredAt() {
    return storedAt;
  }

  public void setStoredAt(final Instant storedAt) {
    this.storedAt = storedAt;
  }

  public Instant getLastSupervisedCheckAt() {
    return lastSupervisedCheckAt;
  }

  public void setLastSupervisedCheckAt(final Instant lastSupervisedCheckAt) {
    this.lastSupervisedCheckAt = lastSupervisedCheckAt;
  }

  public int getConsecutiveOriginFailures() {
    return consecutiveOriginFailures;
  }

  public void setConsecutiveOriginFailures(final int consecutiveOriginFailures) {
    this.consecutiveOriginFailures = consecutiveOriginFailures;
  }
}
