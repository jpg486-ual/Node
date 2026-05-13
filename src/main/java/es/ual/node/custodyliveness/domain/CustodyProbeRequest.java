package es.ual.node.custodyliveness.domain;

import java.time.Instant;
import java.util.List;

/**
 * Inbound/outbound custody liveness probe request.
 *
 * <p>{@code requesterTutorBaseUrl} carries the tutor base URL of the requester so that the
 * custodian peer can persist it in the corresponding {@link CustodyProbeSession#remoteTutorBaseUrl}
 * and use it later when a {@code RETURN_TO_TUTOR} escalation fires.
 *
 * <p>La eliminación explícita por parte del origen va exclusivamente por la vía whitelist. La
 * <em>ausencia</em> de un fragment en {@code keepFragmentIds} del {@code
 * OriginInboundKeepListService} es la señal canónica "origen ya no lo necesita".
 */
public record CustodyProbeRequest(
    String requestId,
    String requesterNodeId,
    String targetNodeId,
    List<CustodyProbeFragment> fragments,
    Instant requestedAt,
    long reverseProbeWindowSeconds,
    String requesterTutorBaseUrl) {

  /**
   * Convenience factory for probe requests without requester tutor info (legacy / outbound probes
   * where the requester tutor is not relevant).
   */
  public static CustodyProbeRequest withoutRequesterTutor(
      final String requestId,
      final String requesterNodeId,
      final String targetNodeId,
      final List<CustodyProbeFragment> fragments,
      final Instant requestedAt,
      final long reverseProbeWindowSeconds) {
    return new CustodyProbeRequest(
        requestId,
        requesterNodeId,
        targetNodeId,
        fragments,
        requestedAt,
        reverseProbeWindowSeconds,
        null);
  }
}
