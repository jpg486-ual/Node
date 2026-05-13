package es.ual.node.recovery.adapters.in.web;

import es.ual.node.recovery.application.TutorFileManifestCustodyService;
import java.util.List;

/** HTTP request payload for {@code POST /recovery/file-manifests}. */
public final class RecoveryStoreFileManifestPayload {

  private String fileId;
  private String requesterNodeId;
  private String requesterPublicKey;
  private String directoryPath;
  private String originalFileName;
  private String originalFileHash;
  private long originalSizeBytes;
  private Long compressedSizeBytes;
  private String compressionAlgorithm;
  private int fragmentCount;
  private long fragmentSize;
  private int redundancyN;
  private int redundancyK;
  private List<String> fragmentHashes;
  private String clientPlacementsJson;
  private String clientBlocksJson;

  /** Converts payload to service request. */
  public TutorFileManifestCustodyService.StoreFileManifestRequest toDomain() {
    return new TutorFileManifestCustodyService.StoreFileManifestRequest(
        fileId,
        requesterNodeId,
        requesterPublicKey,
        directoryPath,
        originalFileName,
        originalFileHash,
        originalSizeBytes,
        compressedSizeBytes,
        compressionAlgorithm,
        fragmentCount,
        fragmentSize,
        redundancyN,
        redundancyK,
        fragmentHashes == null ? List.of() : fragmentHashes,
        clientPlacementsJson,
        clientBlocksJson);
  }

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

  public List<String> getFragmentHashes() {
    return fragmentHashes;
  }

  public void setFragmentHashes(final List<String> fragmentHashes) {
    this.fragmentHashes = fragmentHashes;
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
}
