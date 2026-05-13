package es.ual.node.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * JPA entity for client-side file manifests stored at the origin. Los campos del FileManifest se
 * persisten via columnas normalizadas + tabla auxiliar {@link ClientFileManifestBlockJpaEntity}
 * (una fila por bloque RS o una sintética para legacy single-block). El flag {@code multiBlock}
 * preserva el shape exacto in-memory.
 */
@Entity
@Table(name = "client_file_manifest")
public class ClientFileManifestJpaEntity {

  @Id
  @Column(name = "file_id", nullable = false, length = 64)
  private String fileId;

  @Column(name = "username", nullable = false, length = 128)
  private String username;

  @Column(name = "entry_id", nullable = false, length = 64)
  private String entryId;

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

  @Column(name = "fragment_count", nullable = false)
  private int fragmentCount;

  @Column(name = "fragment_size", nullable = false)
  private long fragmentSize;

  @Column(name = "redundancy_n", nullable = false)
  private int redundancyN;

  @Column(name = "redundancy_k", nullable = false)
  private int redundancyK;

  @Column(name = "symbol_size", nullable = false)
  private int symbolSize;

  @Column(name = "multi_block", nullable = false)
  private boolean multiBlock;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public String getFileId() {
    return fileId;
  }

  public void setFileId(final String fileId) {
    this.fileId = fileId;
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

  public int getFragmentCount() {
    return fragmentCount;
  }

  public void setFragmentCount(final int fragmentCount) {
    this.fragmentCount = fragmentCount;
  }

  public long getFragmentSize() {
    return fragmentSize;
  }

  public void setFragmentSize(final long fragmentSize) {
    this.fragmentSize = fragmentSize;
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

  public int getSymbolSize() {
    return symbolSize;
  }

  public void setSymbolSize(final int symbolSize) {
    this.symbolSize = symbolSize;
  }

  public boolean isMultiBlock() {
    return multiBlock;
  }

  public void setMultiBlock(final boolean multiBlock) {
    this.multiBlock = multiBlock;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(final Instant createdAt) {
    this.createdAt = createdAt;
  }
}
