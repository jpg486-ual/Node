package es.ual.node.fragmentstorage.ports.out;

import java.util.Optional;

/**
 * Outbound port for storing and loading <strong>custody-domain</strong> fragment payload bytes.
 * Separate from the recovery-domain payload port.
 */
public interface CustodyFragmentPayloadPort {

  /** Saves payload bytes for a fragment id. */
  void save(String fragmentId, byte[] payload);

  /** Finds payload bytes for a fragment id. */
  Optional<byte[]> findByFragmentId(String fragmentId);

  /** Returns whether payload exists for a fragment id. */
  boolean exists(String fragmentId);

  /** Deletes payload bytes for a fragment id. */
  void deleteByFragmentId(String fragmentId);
}
