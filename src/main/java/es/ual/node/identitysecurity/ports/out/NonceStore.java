package es.ual.node.identitysecurity.ports.out;

import java.time.Instant;

/** Outbound port for nonce replay protection. */
public interface NonceStore {

  /**
   * Marks a nonce as used when it is absent and not expired.
   *
   * @param nonce nonce value
   * @param expiresAt nonce expiration instant
   * @return {@code true} if nonce was absent and is now stored
   */
  boolean markIfAbsent(String nonce, Instant expiresAt);
}
