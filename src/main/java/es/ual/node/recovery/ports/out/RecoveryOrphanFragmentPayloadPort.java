package es.ual.node.recovery.ports.out;

import java.util.Optional;

/**
 * Outbound port for storing and loading <strong>recovery-domain</strong> orphan fragment payload
 * bytes. Separate from the custody-domain payload port.
 */
public interface RecoveryOrphanFragmentPayloadPort {

  /** Saves payload bytes for a fragment id. */
  void save(String fragmentId, byte[] payload);

  /** Finds payload bytes for a fragment id. */
  Optional<byte[]> findByFragmentId(String fragmentId);

  /** Returns whether payload exists for a fragment id. */
  boolean exists(String fragmentId);

  /** Deletes payload bytes for a fragment id. */
  void deleteByFragmentId(String fragmentId);
}
