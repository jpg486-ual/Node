package es.ual.node.custodyliveness.adapters.in.web;

import es.ual.node.custodyliveness.domain.CustodyProbeRequest;
import java.time.Instant;
import java.util.List;

/** HTTP payload for custody liveness probe request. */
public final class CustodyProbeRequestPayload {

  private String requestId;
  private String requesterNodeId;
  private String targetNodeId;
  private List<CustodyProbeFragmentPayload> fragments;
  private Instant requestedAt;
  private Long reverseProbeWindowSeconds;
  private String requesterTutorBaseUrl;

  public CustodyProbeRequest toDomain() {
    return new CustodyProbeRequest(
        requestId,
        requesterNodeId,
        targetNodeId,
        fragments == null
            ? List.of()
            : fragments.stream().map(CustodyProbeFragmentPayload::toDomain).toList(),
        requestedAt == null ? Instant.now() : requestedAt,
        reverseProbeWindowSeconds == null ? 0L : reverseProbeWindowSeconds,
        requesterTutorBaseUrl);
  }

  public String getRequesterTutorBaseUrl() {
    return requesterTutorBaseUrl;
  }

  public void setRequesterTutorBaseUrl(final String requesterTutorBaseUrl) {
    this.requesterTutorBaseUrl = requesterTutorBaseUrl;
  }

  public String getRequestId() {
    return requestId;
  }

  public void setRequestId(final String requestId) {
    this.requestId = requestId;
  }

  public String getRequesterNodeId() {
    return requesterNodeId;
  }

  public void setRequesterNodeId(final String requesterNodeId) {
    this.requesterNodeId = requesterNodeId;
  }

  public String getTargetNodeId() {
    return targetNodeId;
  }

  public void setTargetNodeId(final String targetNodeId) {
    this.targetNodeId = targetNodeId;
  }

  public List<CustodyProbeFragmentPayload> getFragments() {
    return fragments;
  }

  public void setFragments(final List<CustodyProbeFragmentPayload> fragments) {
    this.fragments = fragments;
  }

  public Instant getRequestedAt() {
    return requestedAt;
  }

  public void setRequestedAt(final Instant requestedAt) {
    this.requestedAt = requestedAt;
  }

  public Long getReverseProbeWindowSeconds() {
    return reverseProbeWindowSeconds;
  }

  public void setReverseProbeWindowSeconds(final Long reverseProbeWindowSeconds) {
    this.reverseProbeWindowSeconds = reverseProbeWindowSeconds;
  }
}
