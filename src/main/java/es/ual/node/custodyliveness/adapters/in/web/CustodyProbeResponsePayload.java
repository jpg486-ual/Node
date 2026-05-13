package es.ual.node.custodyliveness.adapters.in.web;

import es.ual.node.custodyliveness.domain.CustodyProbeResponse;
import java.time.Instant;
import java.util.List;

/** HTTP payload for custody liveness probe response. */
public final class CustodyProbeResponsePayload {

  private String requestId;
  private List<String> stillRequiredFragmentIds;
  private List<String> releasableFragmentIds;
  private boolean reverseProbeRequested;
  private Instant reverseProbeNotBefore;
  private Instant respondedAt;

  public static CustodyProbeResponsePayload fromDomain(final CustodyProbeResponse response) {
    final CustodyProbeResponsePayload payload = new CustodyProbeResponsePayload();
    payload.requestId = response.requestId();
    payload.stillRequiredFragmentIds = response.stillRequiredFragmentIds();
    payload.releasableFragmentIds = response.releasableFragmentIds();
    payload.reverseProbeRequested = response.reverseProbeRequested();
    payload.reverseProbeNotBefore = response.reverseProbeNotBefore();
    payload.respondedAt = response.respondedAt();
    return payload;
  }

  public String getRequestId() {
    return requestId;
  }

  public List<String> getStillRequiredFragmentIds() {
    return stillRequiredFragmentIds;
  }

  public List<String> getReleasableFragmentIds() {
    return releasableFragmentIds;
  }

  public boolean isReverseProbeRequested() {
    return reverseProbeRequested;
  }

  public Instant getReverseProbeNotBefore() {
    return reverseProbeNotBefore;
  }

  public Instant getRespondedAt() {
    return respondedAt;
  }
}
