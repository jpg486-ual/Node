package es.ual.node.negotiation.adapters.in.web;

import es.ual.node.negotiation.domain.FileManifest;
import es.ual.node.negotiation.domain.NegotiationCreateRequest;
import es.ual.node.negotiation.domain.TransferMode;

/** HTTP payload for negotiation creation. */
public final class NegotiationCreatePayload {

  private String requesterNodeId;
  private String targetNodeId;
  private long bucketSize;
  private long expectedStorageBytes;
  private TransferMode transferMode;
  private Integer fragmentCount;
  private String redundancyScheme;
  private int expirationSeconds;
  private FileManifestPayload fileManifest;
  private String requesterSignature;
  private String requesterTutorNodeId;
  private String requesterTutorBaseUrl;

  /**
   * Returns requester node id.
   *
   * @return requester node id
   */
  public String requesterNodeId() {
    return requesterNodeId;
  }

  /**
   * Sets requester node id.
   *
   * @param requesterNodeId requester node id
   */
  public void setRequesterNodeId(final String requesterNodeId) {
    this.requesterNodeId = requesterNodeId;
  }

  /**
   * Returns target node id.
   *
   * @return target node id
   */
  public String targetNodeId() {
    return targetNodeId;
  }

  /**
   * Sets target node id.
   *
   * @param targetNodeId target node id
   */
  public void setTargetNodeId(final String targetNodeId) {
    this.targetNodeId = targetNodeId;
  }

  /**
   * Returns bucket size.
   *
   * @return bucket size
   */
  public long bucketSize() {
    return bucketSize;
  }

  /**
   * Sets bucket size.
   *
   * @param bucketSize bucket size
   */
  public void setBucketSize(final long bucketSize) {
    this.bucketSize = bucketSize;
  }

  /**
   * Returns expected storage bytes.
   *
   * @return expected storage bytes
   */
  public long expectedStorageBytes() {
    return expectedStorageBytes;
  }

  /**
   * Sets expected storage bytes.
   *
   * @param expectedStorageBytes expected storage bytes
   */
  public void setExpectedStorageBytes(final long expectedStorageBytes) {
    this.expectedStorageBytes = expectedStorageBytes;
  }

  /**
   * Returns transfer mode.
   *
   * @return transfer mode
   */
  public TransferMode transferMode() {
    return transferMode;
  }

  /**
   * Sets transfer mode.
   *
   * @param transferMode transfer mode
   */
  public void setTransferMode(final TransferMode transferMode) {
    this.transferMode = transferMode;
  }

  /**
   * Returns fragment count.
   *
   * @return fragment count
   */
  public Integer fragmentCount() {
    return fragmentCount;
  }

  /**
   * Sets fragment count.
   *
   * @param fragmentCount fragment count
   */
  public void setFragmentCount(final Integer fragmentCount) {
    this.fragmentCount = fragmentCount;
  }

  /**
   * Returns redundancy scheme.
   *
   * @return redundancy scheme
   */
  public String redundancyScheme() {
    return redundancyScheme;
  }

  /**
   * Sets redundancy scheme.
   *
   * @param redundancyScheme redundancy scheme
   */
  public void setRedundancyScheme(final String redundancyScheme) {
    this.redundancyScheme = redundancyScheme;
  }

  /**
   * Returns expiration seconds.
   *
   * @return expiration seconds
   */
  public int expirationSeconds() {
    return expirationSeconds;
  }

  /**
   * Sets expiration seconds.
   *
   * @param expirationSeconds expiration seconds
   */
  public void setExpirationSeconds(final int expirationSeconds) {
    this.expirationSeconds = expirationSeconds;
  }

  /**
   * Returns file manifest payload.
   *
   * @return file manifest payload
   */
  public FileManifestPayload fileManifest() {
    return fileManifest;
  }

  /**
   * Sets file manifest payload.
   *
   * @param fileManifest file manifest payload
   */
  public void setFileManifest(final FileManifestPayload fileManifest) {
    this.fileManifest = fileManifest;
  }

  /**
   * Returns requester signature.
   *
   * @return requester signature
   */
  public String requesterSignature() {
    return requesterSignature;
  }

  /**
   * Sets requester signature.
   *
   * @param requesterSignature requester signature
   */
  public void setRequesterSignature(final String requesterSignature) {
    this.requesterSignature = requesterSignature;
  }

  /**
   * Returns optional tutor node id of the requester.
   *
   * @return requester tutor node id or {@code null} when absent
   */
  public String requesterTutorNodeId() {
    return requesterTutorNodeId;
  }

  /**
   * Sets requester tutor node id.
   *
   * @param requesterTutorNodeId requester tutor node id
   */
  public void setRequesterTutorNodeId(final String requesterTutorNodeId) {
    this.requesterTutorNodeId = requesterTutorNodeId;
  }

  /**
   * Returns optional tutor base URL of the requester.
   *
   * @return requester tutor base URL or {@code null} when absent
   */
  public String requesterTutorBaseUrl() {
    return requesterTutorBaseUrl;
  }

  /**
   * Sets requester tutor base URL.
   *
   * @param requesterTutorBaseUrl requester tutor base URL
   */
  public void setRequesterTutorBaseUrl(final String requesterTutorBaseUrl) {
    this.requesterTutorBaseUrl = requesterTutorBaseUrl;
  }

  /**
   * Converts payload to domain request.
   *
   * @return domain request
   */
  public NegotiationCreateRequest toDomain() {
    final FileManifest manifest = fileManifest == null ? null : fileManifest.toDomain();
    return new NegotiationCreateRequest(
        requesterNodeId,
        targetNodeId,
        bucketSize,
        expectedStorageBytes,
        transferMode,
        fragmentCount,
        redundancyScheme,
        expirationSeconds,
        manifest,
        requesterSignature,
        requesterTutorNodeId,
        requesterTutorBaseUrl);
  }
}
