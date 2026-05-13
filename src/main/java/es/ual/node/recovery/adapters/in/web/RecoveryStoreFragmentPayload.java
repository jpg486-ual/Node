package es.ual.node.recovery.adapters.in.web;

import es.ual.node.recovery.application.TutorRecoveryService;

/** HTTP payload for storing a fragment in tutor custody. */
public final class RecoveryStoreFragmentPayload {

  private String fragmentId;
  private String agreementId;
  private String requesterNodeId;
  private String requesterPublicKey;
  private String checksumAlgorithm;
  private String checksum;
  private String payloadBase64;

  /**
   * Converts HTTP payload into application request.
   *
   * @return store request
   */
  public TutorRecoveryService.StoreRecoveryFragmentRequest toDomain() {
    return new TutorRecoveryService.StoreRecoveryFragmentRequest(
        fragmentId,
        agreementId,
        requesterNodeId,
        requesterPublicKey,
        checksumAlgorithm,
        checksum,
        payloadBase64);
  }

  public String getFragmentId() {
    return fragmentId;
  }

  public void setFragmentId(final String fragmentId) {
    this.fragmentId = fragmentId;
  }

  public String getAgreementId() {
    return agreementId;
  }

  public void setAgreementId(final String agreementId) {
    this.agreementId = agreementId;
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

  public String getChecksumAlgorithm() {
    return checksumAlgorithm;
  }

  public void setChecksumAlgorithm(final String checksumAlgorithm) {
    this.checksumAlgorithm = checksumAlgorithm;
  }

  public String getChecksum() {
    return checksum;
  }

  public void setChecksum(final String checksum) {
    this.checksum = checksum;
  }

  public String getPayloadBase64() {
    return payloadBase64;
  }

  public void setPayloadBase64(final String payloadBase64) {
    this.payloadBase64 = payloadBase64;
  }
}
