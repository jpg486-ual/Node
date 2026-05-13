package es.ual.node.custodyliveness.application;

import java.util.Map;

/** Point-in-time snapshot for custody liveness operational metrics. */
public record CustodyLivenessMetricsSnapshot(
    long probesInboundTotal,
    long probesOutboundScheduledTotal,
    long probesOutboundDeduplicatedTotal,
    long probesOutboundSuccessTotal,
    long probesOutboundFailureTotal,
    long transitionsActiveTotal,
    long transitionsSuspectTotal,
    long transitionsUnresponsiveTotal,
    long transitionsEscalatedTotal,
    long escalationDeferredTotal,
    long expiryEscalationTotal) {

  /**
   * Returns named metrics map for ops payloads.
   *
   * @return immutable metrics map
   */
  public Map<String, Number> asNamedMetrics() {
    return Map.ofEntries(
        Map.entry("custody.liveness.inbound.total", probesInboundTotal),
        Map.entry("custody.liveness.outbound.scheduled.total", probesOutboundScheduledTotal),
        Map.entry("custody.liveness.outbound.deduplicated.total", probesOutboundDeduplicatedTotal),
        Map.entry("custody.liveness.outbound.success.total", probesOutboundSuccessTotal),
        Map.entry("custody.liveness.outbound.failure.total", probesOutboundFailureTotal),
        Map.entry("custody.liveness.transition.active.total", transitionsActiveTotal),
        Map.entry("custody.liveness.transition.suspect.total", transitionsSuspectTotal),
        Map.entry("custody.liveness.transition.unresponsive.total", transitionsUnresponsiveTotal),
        Map.entry("custody.liveness.transition.escalated.total", transitionsEscalatedTotal),
        Map.entry("custody.liveness.escalation.deferred.total", escalationDeferredTotal),
        Map.entry("custody.liveness.expiry.escalation.total", expiryEscalationTotal));
  }
}
