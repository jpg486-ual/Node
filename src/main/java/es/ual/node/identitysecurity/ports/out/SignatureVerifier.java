package es.ual.node.identitysecurity.ports.out;

import java.security.PublicKey;

/** Outbound port for cryptographic signature verification. */
public interface SignatureVerifier {

  /**
   * Verifies a signature for a payload and public key.
   *
   * @param algorithm signature algorithm name
   * @param payload canonical payload bytes
   * @param signature signature bytes
   * @param publicKey public key used for verification
   * @return {@code true} if signature is valid
   */
  boolean verify(String algorithm, byte[] payload, byte[] signature, PublicKey publicKey);
}
