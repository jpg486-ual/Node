package es.ual.node.custodyliveness.ports.out;

import es.ual.node.custodyliveness.domain.CustodyProbeSession;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Outbound port for persisting custody liveness sessions. */
public interface CustodyProbeSessionPort {

  void save(CustodyProbeSession session);

  Optional<CustodyProbeSession> findById(String sessionId);

  List<CustodyProbeSession> findByRemoteNodeId(String remoteNodeId);

  /** Returns all sessions ordered by latest updates first. */
  List<CustodyProbeSession> findAll();

  List<CustodyProbeSession> findDueOutbound(Instant now, int limit);
}
