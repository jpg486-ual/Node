package es.ual.node.custodyliveness.adapters.in.web;

import es.ual.node.custodyliveness.domain.CustodyProbeFragment;

/** HTTP payload for a custody probe fragment. */
public final class CustodyProbeFragmentPayload {

  private String fragmentId;
  private String agreementId;
  private String checksum;
  private Long sizeBytes;

  public CustodyProbeFragment toDomain() {
    return new CustodyProbeFragment(
        fragmentId, agreementId, checksum, sizeBytes == null ? 0L : sizeBytes);
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

  public String getChecksum() {
    return checksum;
  }

  public void setChecksum(final String checksum) {
    this.checksum = checksum;
  }

  public Long getSizeBytes() {
    return sizeBytes;
  }

  public void setSizeBytes(final Long sizeBytes) {
    this.sizeBytes = sizeBytes;
  }
}
