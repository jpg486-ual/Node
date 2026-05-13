package es.ual.node.recovery.adapters.in.web;

import es.ual.node.recovery.application.TutorRecoveryService;
import java.util.List;

/** HTTP payload for reconstructing a file from custodied RS fragments. */
public final class RecoveryReconstructPayload {

  private String fileId;
  private String expectedOriginalHash;
  private int redundancyN;
  private int redundancyK;
  private int symbolSize;
  private List<FragmentReferencePayload> fragments;

  /**
   * Converts HTTP payload to domain reconstruction request.
   *
   * @return reconstruction request
   */
  public TutorRecoveryService.ReconstructRecoveryFragmentsRequest toDomain() {
    final List<TutorRecoveryService.ReconstructFragmentReference> references =
        fragments == null
            ? List.of()
            : fragments.stream().map(FragmentReferencePayload::toDomain).toList();

    return new TutorRecoveryService.ReconstructRecoveryFragmentsRequest(
        fileId, expectedOriginalHash, redundancyN, redundancyK, symbolSize, references);
  }

  public String getFileId() {
    return fileId;
  }

  public void setFileId(final String fileId) {
    this.fileId = fileId;
  }

  public String getExpectedOriginalHash() {
    return expectedOriginalHash;
  }

  public void setExpectedOriginalHash(final String expectedOriginalHash) {
    this.expectedOriginalHash = expectedOriginalHash;
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

  public List<FragmentReferencePayload> getFragments() {
    return fragments;
  }

  public void setFragments(final List<FragmentReferencePayload> fragments) {
    this.fragments = fragments;
  }

  /** Fragment reference payload used during reconstruction. */
  public static final class FragmentReferencePayload {

    private String fragmentId;
    private int index;
    private boolean parity;

    /**
     * Converts payload into domain fragment reference.
     *
     * @return fragment reference
     */
    public TutorRecoveryService.ReconstructFragmentReference toDomain() {
      return new TutorRecoveryService.ReconstructFragmentReference(fragmentId, index, parity);
    }

    public String getFragmentId() {
      return fragmentId;
    }

    public void setFragmentId(final String fragmentId) {
      this.fragmentId = fragmentId;
    }

    public int getIndex() {
      return index;
    }

    public void setIndex(final int index) {
      this.index = index;
    }

    public boolean isParity() {
      return parity;
    }

    public void setParity(final boolean parity) {
      this.parity = parity;
    }
  }
}
