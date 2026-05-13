package es.ual.node.custodyliveness.ports.out;

import es.ual.node.custodyliveness.domain.CustodyProbeRequest;
import es.ual.node.custodyliveness.domain.CustodyProbeResponse;

/** Outbound port for sending remote custody liveness probes. */
public interface RemoteCustodyProbeClientPort {

  /**
   * Sends a custody liveness probe to a remote node.
   *
   * @param remoteNodeId remote node identifier
   * @param request request payload
   * @return remote response
   */
  CustodyProbeResponse probe(String remoteNodeId, CustodyProbeRequest request);
}
