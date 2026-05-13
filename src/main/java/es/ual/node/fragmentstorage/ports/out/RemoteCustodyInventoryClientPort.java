package es.ual.node.fragmentstorage.ports.out;

import es.ual.node.fragmentstorage.domain.CustodyInventoryItem;
import java.util.List;

/**
 * Outbound port to query a peer custodian's inventory of fragments held on behalf of this node.
 * Used by the recovery worker to learn TTL-remaining per placement and detect at-risk fragments.
 *
 * <p>The implementation issues {@code GET /custody/fragments/by-requester/{nodeId}} signed with the
 * local node identity. Cross-owner inventory probing is rejected by custodians with HTTP 403, so
 * the {@code nodeId} passed must equal the calling node identity.
 */
public interface RemoteCustodyInventoryClientPort {

  /**
   * Lists the inventory of fragments {@code custodianBaseUrl} holds for {@code nodeId} (the local
   * origin). Returns an empty list on transport errors or non-2xx responses (the worker treats
   * absent inventory as "everything gone" and falls back to redistribution).
   *
   * @param custodianBaseUrl HTTP base URL of the peer custodian (no trailing slash)
   * @param nodeId origin node identifier, must equal the local node id
   * @return list of inventory items, possibly empty
   */
  List<CustodyInventoryItem> fetchInventory(String custodianBaseUrl, String nodeId);
}
