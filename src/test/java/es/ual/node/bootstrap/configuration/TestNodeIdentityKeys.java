package es.ual.node.bootstrap.configuration;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

/** Test utility for generating valid node identity key properties. */
public final class TestNodeIdentityKeys {

  private TestNodeIdentityKeys() {}

  /**
   * Generates property values for node identity configuration.
   *
   * @return property values with valid public/private key pair
   */
  public static String[] generatePropertyValues() {
    try {
      KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC");
      keyPairGenerator.initialize(256);
      KeyPair keyPair = keyPairGenerator.generateKeyPair();

      String publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
      String privateKeyBase64 =
          Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());

      return new String[] {
        "node.identity.public-key-base64=" + publicKeyBase64,
        "node.identity.private-key-base64=" + privateKeyBase64,
        "node.identity.key-algorithm=EC"
      };
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to generate test node identity key pair", exception);
    }
  }
}
