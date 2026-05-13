package es.ual.node.reedsolomon.adapters.out.memory;

import es.ual.node.reedsolomon.ports.out.RsIntegrityVerifierPort;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Pattern;

/** In-memory SHA-256 verifier for reconstructed RS payload integrity. */
public class InMemoryRsIntegrityVerifier implements RsIntegrityVerifierPort {

  private static final Pattern SHA_256_HEX = Pattern.compile("^[a-fA-F0-9]{64}$");

  @Override
  public boolean verify(final byte[] bytes, final String expectedHash) {
    if (bytes == null || bytes.length == 0) {
      throw new IllegalArgumentException("bytes must not be empty");
    }
    if (expectedHash == null || expectedHash.isBlank()) {
      throw new IllegalArgumentException("expectedHash must not be blank");
    }

    final String normalizedExpectedHash = expectedHash.trim().toLowerCase(Locale.ROOT);
    if (!SHA_256_HEX.matcher(normalizedExpectedHash).matches()) {
      throw new IllegalArgumentException("expectedHash must be a valid SHA-256 hex value");
    }

    final byte[] expectedBytes = hexToBytes(normalizedExpectedHash);
    final byte[] actualBytes = sha256(bytes);
    return MessageDigest.isEqual(actualBytes, expectedBytes);
  }

  private byte[] sha256(final byte[] bytes) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return digest.digest(bytes);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 algorithm is not available", ex);
    }
  }

  private byte[] hexToBytes(final String hex) {
    final byte[] bytes = new byte[hex.length() / 2];
    for (int i = 0; i < hex.length(); i += 2) {
      final int high = Character.digit(hex.charAt(i), 16);
      final int low = Character.digit(hex.charAt(i + 1), 16);
      bytes[i / 2] = (byte) ((high << 4) + low);
    }
    return bytes;
  }
}
