package es.ual.node.identitysecurity.adapters.out.memory;

import es.ual.node.identitysecurity.ports.out.NonceStore;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** In-memory nonce store with expiration support. */
public class InMemoryNonceStore implements NonceStore {

  private final ConcurrentMap<String, Instant> expiryByNonce = new ConcurrentHashMap<>();

  /** {@inheritDoc} */
  @Override
  public synchronized boolean markIfAbsent(final String nonce, final Instant expiresAt) {
    if (nonce == null || nonce.isBlank()) {
      throw new IllegalArgumentException("nonce must not be blank");
    }
    if (expiresAt == null) {
      throw new IllegalArgumentException("expiresAt must not be null");
    }

    final Instant now = Instant.now();
    cleanupExpired(now);

    final String normalized = nonce.trim();
    if (expiryByNonce.containsKey(normalized)) {
      return false;
    }
    expiryByNonce.put(normalized, expiresAt);
    return true;
  }

  private void cleanupExpired(final Instant now) {
    expiryByNonce.forEach(
        (nonce, expiry) -> {
          if (!expiry.isAfter(now)) {
            expiryByNonce.remove(nonce, expiry);
          }
        });
  }
}
