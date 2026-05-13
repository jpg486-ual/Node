package es.ual.node.identitysecurity.ports.out;

import java.security.PublicKey;
import java.util.Optional;

/** Outbound port for registered node public keys. */
public interface PublicKeyRegistry {

  /**
   * Finds a registered public key by node id.
   *
   * @param nodeId node identifier
   * @return matching public key if found
   */
  Optional<PublicKey> findByNodeId(String nodeId);

  /**
   * Indicates if a node is registered.
   *
   * @param nodeId node identifier
   * @return {@code true} if node is registered
   */
  boolean isRegistered(String nodeId);
}
