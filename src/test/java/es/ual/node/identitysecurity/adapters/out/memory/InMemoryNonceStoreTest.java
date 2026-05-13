package es.ual.node.identitysecurity.adapters.out.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Unit tests para {@link InMemoryNonceStore}.
 *
 * <p>Cubre el contrato anti-replay: aceptación primera, rechazo on replay, eviction de nonces
 * expirados, y guards defensivos contra blank/null. NO ejercita concurrencia (synchronized está
 * congelado por la JLS).
 */
class InMemoryNonceStoreTest {

  @Test
  void markIfAbsent_acceptsNewNonceFirstTime() {
    InMemoryNonceStore store = new InMemoryNonceStore();
    Instant future = Instant.now().plusSeconds(60);

    boolean accepted = store.markIfAbsent("nonce-fresh", future);

    assertTrue(accepted, "first call with new nonce must accept");
  }

  @Test
  void markIfAbsent_rejectsReplayedNonceWithinWindow() {
    InMemoryNonceStore store = new InMemoryNonceStore();
    Instant future = Instant.now().plusSeconds(60);

    assertTrue(store.markIfAbsent("nonce-replay", future));
    boolean second = store.markIfAbsent("nonce-replay", future);

    assertFalse(second, "second call with same nonce must reject");
  }

  @Test
  void markIfAbsent_evictsExpiredNoncesAndAcceptsAgain() throws InterruptedException {
    InMemoryNonceStore store = new InMemoryNonceStore();
    Instant alreadyPast = Instant.now().minusSeconds(1);

    boolean firstAccepted = store.markIfAbsent("nonce-expiring", alreadyPast);
    assertTrue(firstAccepted, "stored even with past expiry (cleanup runs before put)");

    Thread.sleep(10);

    boolean otherAccepted = store.markIfAbsent("nonce-other", Instant.now().plusSeconds(60));
    assertTrue(otherAccepted, "other nonce accepted; cleanup is triggered on this call");

    boolean reReplay = store.markIfAbsent("nonce-expiring", Instant.now().plusSeconds(60));
    assertTrue(reReplay, "expired nonce was evicted and is now accepted again");
  }

  @Test
  void markIfAbsent_throwsWhenNonceBlank() {
    InMemoryNonceStore store = new InMemoryNonceStore();
    Instant future = Instant.now().plusSeconds(60);

    assertThrows(IllegalArgumentException.class, () -> store.markIfAbsent("", future));
    assertThrows(IllegalArgumentException.class, () -> store.markIfAbsent("   ", future));
    assertThrows(IllegalArgumentException.class, () -> store.markIfAbsent(null, future));
  }

  @Test
  void markIfAbsent_throwsWhenExpiresAtNull() {
    InMemoryNonceStore store = new InMemoryNonceStore();

    assertThrows(IllegalArgumentException.class, () -> store.markIfAbsent("nonce-x", null));
  }
}
