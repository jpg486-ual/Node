package es.ual.node.reedsolomon.adapters.out.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link InMemoryRsIntegrityVerifier}. */
class InMemoryRsIntegrityVerifierTest {

  @Test
  void shouldReturnTrueWhenHashMatchesPayload() {
    final InMemoryRsIntegrityVerifier verifier = new InMemoryRsIntegrityVerifier();
    final byte[] payload = "payload-hash".getBytes(StandardCharsets.UTF_8);

    assertTrue(verifier.verify(payload, sha256Hex(payload)));
  }

  @Test
  void shouldReturnFalseWhenHashDoesNotMatchPayload() {
    final InMemoryRsIntegrityVerifier verifier = new InMemoryRsIntegrityVerifier();
    final byte[] payload = "payload-hash".getBytes(StandardCharsets.UTF_8);

    assertFalse(verifier.verify(payload, "0".repeat(64)));
  }

  @Test
  void shouldRejectBlankExpectedHash() {
    final InMemoryRsIntegrityVerifier verifier = new InMemoryRsIntegrityVerifier();
    final byte[] payload = "payload-hash".getBytes(StandardCharsets.UTF_8);

    assertThrows(IllegalArgumentException.class, () -> verifier.verify(payload, " "));
  }

  private String sha256Hex(final byte[] payload) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      final byte[] bytes = digest.digest(payload);
      final StringBuilder builder = new StringBuilder(bytes.length * 2);
      for (byte value : bytes) {
        builder.append(String.format("%02x", value));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 algorithm is not available", ex);
    }
  }
}
