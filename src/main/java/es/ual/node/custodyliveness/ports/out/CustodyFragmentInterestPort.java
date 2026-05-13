package es.ual.node.custodyliveness.ports.out;

import es.ual.node.custodyliveness.domain.CustodyProbeFragment;

/** Outbound port to evaluate whether local node is still interested in a fragment. */
public interface CustodyFragmentInterestPort {

  /**
   * Returns whether local node still requires a fragment announced by remote custodian.
   *
   * @param fragment announced fragment
   * @param requesterNodeId remote requester node id
   * @return {@code true} when still required
   */
  boolean isStillRequired(CustodyProbeFragment fragment, String requesterNodeId);
}
