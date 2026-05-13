package es.ual.node.custodyliveness.adapters.out.ops;

import es.ual.node.custodyliveness.domain.CustodyEscalationPolicy;
import es.ual.node.custodyliveness.domain.CustodyProbeFragment;
import es.ual.node.custodyliveness.domain.CustodyProbeSession;
import es.ual.node.custodyliveness.ports.out.CustodyEscalationPort;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Default escalation adapter that emits structured operational logs. */
public class LoggingCustodyEscalationPort implements CustodyEscalationPort {

  private static final Logger LOGGER = LoggerFactory.getLogger(LoggingCustodyEscalationPort.class);

  @Override
  public void handleUnresponsive(
      final CustodyProbeSession session,
      final List<CustodyProbeFragment> fragments,
      final String reason,
      final Instant detectedAt,
      final CustodyEscalationPolicy policy) {
    if (session == null || detectedAt == null || policy == null) {
      throw new IllegalArgumentException("session, detectedAt and policy must not be null");
    }

    final int fragmentCount = fragments == null ? 0 : fragments.size();
    if (policy == CustodyEscalationPolicy.RETURN_TO_TUTOR) {
      LOGGER
          .atWarn()
          .setMessage("Custody liveness escalation requested with RETURN_TO_TUTOR policy")
          .addKeyValue("sessionId", session.sessionId())
          .addKeyValue("remoteNodeId", session.remoteNodeId())
          .addKeyValue("fragmentCount", fragmentCount)
          .addKeyValue("detectedAt", detectedAt)
          .addKeyValue("reason", reason)
          .log();
      return;
    }

    LOGGER
        .atWarn()
        .setMessage("Custody liveness escalation applied with KEEP_AND_ALERT policy")
        .addKeyValue("sessionId", session.sessionId())
        .addKeyValue("remoteNodeId", session.remoteNodeId())
        .addKeyValue("fragmentCount", fragmentCount)
        .addKeyValue("detectedAt", detectedAt)
        .addKeyValue("reason", reason)
        .log();
  }
}
