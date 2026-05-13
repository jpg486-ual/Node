package es.ual.node.identitysecurity.adapters.out.memory;

import es.ual.node.identitysecurity.ports.out.PublicKeyRegistry;
import java.security.PublicKey;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** In-memory implementation of {@link PublicKeyRegistry}. */
public class InMemoryPublicKeyRegistry implements PublicKeyRegistry {

  private final ConcurrentMap<String, PublicKey> keysByNodeId = new ConcurrentHashMap<>();

  /**
   * Registers or updates a node public key.
   *
   * @param nodeId node identifier
   * @param publicKey node public key
   */
  public void register(final String nodeId, final PublicKey publicKey) {
    if (nodeId == null || nodeId.isBlank()) {
      throw new IllegalArgumentException("nodeId must not be blank");
    }
    if (publicKey == null) {
      throw new IllegalArgumentException("publicKey must not be null");
    }
    keysByNodeId.put(nodeId.trim(), publicKey);
  }

  /** {@inheritDoc} */
  @Override
  public Optional<PublicKey> findByNodeId(final String nodeId) {
    if (nodeId == null || nodeId.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(keysByNodeId.get(nodeId.trim()));
  }

  /** {@inheritDoc} */
  @Override
  public boolean isRegistered(final String nodeId) {
    return findByNodeId(nodeId).isPresent();
  }
}
