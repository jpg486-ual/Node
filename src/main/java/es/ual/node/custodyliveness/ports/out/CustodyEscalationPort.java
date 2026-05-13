package es.ual.node.custodyliveness.ports.out;

import es.ual.node.custodyliveness.domain.CustodyEscalationPolicy;
import es.ual.node.custodyliveness.domain.CustodyProbeFragment;
import es.ual.node.custodyliveness.domain.CustodyProbeSession;
import java.time.Instant;
import java.util.List;

/** Outbound port for unresponsive custody escalation actions. */
public interface CustodyEscalationPort {

  /**
   * Handles unresponsive remote custody according to policy.
   *
   * @param session affected session
   * @param fragments last known fragment inventory for requester
   * @param reason failure reason
   * @param detectedAt detection timestamp
   * @param policy policy to apply
   */
  void handleUnresponsive(
      CustodyProbeSession session,
      List<CustodyProbeFragment> fragments,
      String reason,
      Instant detectedAt,
      CustodyEscalationPolicy policy);
}
