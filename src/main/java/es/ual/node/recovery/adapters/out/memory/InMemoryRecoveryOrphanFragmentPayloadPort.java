package es.ual.node.recovery.adapters.out.memory;

import es.ual.node.recovery.ports.out.RecoveryOrphanFragmentPayloadPort;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory payload store for recovery-domain orphan fragment bytes. */
public final class InMemoryRecoveryOrphanFragmentPayloadPort
    implements RecoveryOrphanFragmentPayloadPort {

  private final Map<String, byte[]> payloadByFragmentId = new ConcurrentHashMap<>();

  @Override
  public void save(final String fragmentId, final byte[] payload) {
    if (fragmentId == null || fragmentId.isBlank()) {
      throw new IllegalArgumentException("fragmentId must not be blank");
    }
    if (payload == null || payload.length == 0) {
      throw new IllegalArgumentException("payload must not be null or empty");
    }
    payloadByFragmentId.put(fragmentId.trim(), payload.clone());
  }

  @Override
  public Optional<byte[]> findByFragmentId(final String fragmentId) {
    if (fragmentId == null || fragmentId.isBlank()) {
      return Optional.empty();
    }
    final byte[] value = payloadByFragmentId.get(fragmentId.trim());
    return value == null ? Optional.empty() : Optional.of(value.clone());
  }

  @Override
  public boolean exists(final String fragmentId) {
    if (fragmentId == null || fragmentId.isBlank()) {
      return false;
    }
    return payloadByFragmentId.containsKey(fragmentId.trim());
  }

  @Override
  public void deleteByFragmentId(final String fragmentId) {
    if (fragmentId == null || fragmentId.isBlank()) {
      return;
    }
    payloadByFragmentId.remove(fragmentId.trim());
  }
}
