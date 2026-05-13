package es.ual.node.negotiation.adapters.in.web;

import es.ual.node.negotiation.domain.FileManifest;
import java.util.List;

/** HTTP payload for file manifest exchange. */
public final class FileManifestPayload {

  private String fileId;
  private String directoryPath;
  private String originalFileName;
  private long originalSizeBytes;
  private Long compressedSizeBytes;
  private String compressionAlgorithm;
  private String originalFileHash;
  private int fragmentCount;
  private long fragmentSize;
  private int redundancyN;
  private int redundancyK;
  private List<String> fragmentHashes;

  /**
   * Returns file id.
   *
   * @return file id
   */
  public String fileId() {
    return fileId;
  }

  /**
   * Sets file id.
   *
   * @param fileId file id
   */
  public void setFileId(final String fileId) {
    this.fileId = fileId;
  }

  /**
   * Returns original file name.
   *
   * @return original file name
   */
  public String originalFileName() {
    return originalFileName;
  }

  /**
   * Sets original file name.
   *
   * @param originalFileName original file name
   */
  public void setOriginalFileName(final String originalFileName) {
    this.originalFileName = originalFileName;
  }

  /**
   * Returns original size bytes.
   *
   * @return original size bytes
   */
  public long originalSizeBytes() {
    return originalSizeBytes;
  }

  /**
   * Sets original size bytes.
   *
   * @param originalSizeBytes original size bytes
   */
  public void setOriginalSizeBytes(final long originalSizeBytes) {
    this.originalSizeBytes = originalSizeBytes;
  }

  /**
   * Returns compressed size bytes.
   *
   * @return compressed size bytes
   */
  public Long compressedSizeBytes() {
    return compressedSizeBytes;
  }

  /**
   * Sets compressed size bytes.
   *
   * @param compressedSizeBytes compressed size bytes
   */
  public void setCompressedSizeBytes(final Long compressedSizeBytes) {
    this.compressedSizeBytes = compressedSizeBytes;
  }

  /**
   * Returns compression algorithm.
   *
   * @return compression algorithm
   */
  public String compressionAlgorithm() {
    return compressionAlgorithm;
  }

  /**
   * Sets compression algorithm.
   *
   * @param compressionAlgorithm compression algorithm
   */
  public void setCompressionAlgorithm(final String compressionAlgorithm) {
    this.compressionAlgorithm = compressionAlgorithm;
  }

  /**
   * Returns original file hash.
   *
   * @return original file hash
   */
  public String originalFileHash() {
    return originalFileHash;
  }

  /**
   * Sets original file hash.
   *
   * @param originalFileHash original file hash
   */
  public void setOriginalFileHash(final String originalFileHash) {
    this.originalFileHash = originalFileHash;
  }

  /**
   * Returns fragment count.
   *
   * @return fragment count
   */
  public int fragmentCount() {
    return fragmentCount;
  }

  /**
   * Sets fragment count.
   *
   * @param fragmentCount fragment count
   */
  public void setFragmentCount(final int fragmentCount) {
    this.fragmentCount = fragmentCount;
  }

  /**
   * Returns fragment size.
   *
   * @return fragment size
   */
  public long fragmentSize() {
    return fragmentSize;
  }

  /**
   * Sets fragment size.
   *
   * @param fragmentSize fragment size
   */
  public void setFragmentSize(final long fragmentSize) {
    this.fragmentSize = fragmentSize;
  }

  /**
   * Returns redundancy n.
   *
   * @return redundancy n
   */
  public int redundancyN() {
    return redundancyN;
  }

  /**
   * Sets redundancy n.
   *
   * @param redundancyN redundancy n
   */
  public void setRedundancyN(final int redundancyN) {
    this.redundancyN = redundancyN;
  }

  /**
   * Returns redundancy k.
   *
   * @return redundancy k
   */
  public int redundancyK() {
    return redundancyK;
  }

  /**
   * Sets redundancy k.
   *
   * @param redundancyK redundancy k
   */
  public void setRedundancyK(final int redundancyK) {
    this.redundancyK = redundancyK;
  }

  /**
   * Returns fragment hashes.
   *
   * @return fragment hashes
   */
  public List<String> fragmentHashes() {
    return fragmentHashes;
  }

  /**
   * Sets fragment hashes.
   *
   * @param fragmentHashes fragment hashes
   */
  public void setFragmentHashes(final List<String> fragmentHashes) {
    this.fragmentHashes = fragmentHashes;
  }

  /**
   * Converts payload to domain manifest.
   *
   * @return domain manifest
   */
  public FileManifest toDomain() {
    return new FileManifest(
        fileId,
        directoryPath == null || directoryPath.isBlank() ? "/" : directoryPath,
        originalFileName,
        originalSizeBytes,
        compressedSizeBytes,
        compressionAlgorithm,
        originalFileHash,
        fragmentCount,
        fragmentSize,
        redundancyN,
        redundancyK,
        fragmentHashes == null ? List.of() : fragmentHashes);
  }

  /**
   * Returns directory path of the file in the originating node FS.
   *
   * @return directory path or null when not provided
   */
  public String directoryPath() {
    return directoryPath;
  }

  /**
   * Sets directory path.
   *
   * @param directoryPath directory path
   */
  public void setDirectoryPath(final String directoryPath) {
    this.directoryPath = directoryPath;
  }
}
