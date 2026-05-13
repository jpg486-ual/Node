package es.ual.node.identitysecurity.application;

import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

/** Immutable runtime context representing local node cryptographic identity. */
public final class NodeIdentityContext {

  private final String nodeId;
  private final PublicKey publicKey;
  private final PrivateKey privateKey;

  /**
   * Creates identity context.
   *
   * @param nodeId derived node identifier
   * @param publicKey node public key
   * @param privateKey node private key
   */
  public NodeIdentityContext(
      final String nodeId, final PublicKey publicKey, final PrivateKey privateKey) {
    if (nodeId == null || nodeId.isBlank()) {
      throw new IllegalArgumentException("nodeId must not be blank");
    }
    if (publicKey == null || privateKey == null) {
      throw new IllegalArgumentException("publicKey and privateKey must not be null");
    }

    this.nodeId = nodeId;
    this.publicKey = publicKey;
    this.privateKey = privateKey;
  }

  /**
   * Returns derived local node identifier.
   *
   * @return derived node identifier
   */
  public String nodeId() {
    return nodeId;
  }

  /**
   * Returns local node public key.
   *
   * @return public key
   */
  public PublicKey publicKey() {
    return publicKey;
  }

  /**
   * Signs payload with local private key.
   *
   * @param signatureAlgorithm signature algorithm
   * @param payload payload bytes
   * @return Base64 signature
   */
  public String signBase64(final String signatureAlgorithm, final byte[] payload) {
    if (signatureAlgorithm == null || signatureAlgorithm.isBlank()) {
      throw new IllegalArgumentException("signatureAlgorithm must not be blank");
    }
    if (payload == null) {
      throw new IllegalArgumentException("payload must not be null");
    }

    try {
      final Signature signature = Signature.getInstance(signatureAlgorithm);
      signature.initSign(privateKey);
      signature.update(payload);
      return Base64.getEncoder().encodeToString(signature.sign());
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Unable to sign payload with local node key", exception);
    }
  }
}
