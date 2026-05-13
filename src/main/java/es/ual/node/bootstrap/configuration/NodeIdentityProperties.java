package es.ual.node.bootstrap.configuration;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Node identity properties used to bootstrap cryptographic identity. */
@ConfigurationProperties(prefix = "node.identity")
public class NodeIdentityProperties {

  private String publicKeyBase64;
  private String privateKeyBase64;
  private String keyAlgorithm = "EC";
  private List<String> trustedPublicKeys = new ArrayList<>();

  /**
   * Returns node public key encoded as Base64 X.509 bytes.
   *
   * @return Base64 public key
   */
  public String getPublicKeyBase64() {
    return publicKeyBase64;
  }

  /**
   * Sets node public key encoded as Base64 X.509 bytes.
   *
   * @param publicKeyBase64 Base64 public key
   */
  public void setPublicKeyBase64(final String publicKeyBase64) {
    this.publicKeyBase64 = publicKeyBase64;
  }

  /**
   * Returns node private key encoded as Base64 PKCS#8 bytes.
   *
   * @return Base64 private key
   */
  public String getPrivateKeyBase64() {
    return privateKeyBase64;
  }

  /**
   * Sets node private key encoded as Base64 PKCS#8 bytes.
   *
   * @param privateKeyBase64 Base64 private key
   */
  public void setPrivateKeyBase64(final String privateKeyBase64) {
    this.privateKeyBase64 = privateKeyBase64;
  }

  /**
   * Returns key algorithm used for key material parsing.
   *
   * @return key algorithm
   */
  public String getKeyAlgorithm() {
    return keyAlgorithm;
  }

  /**
   * Sets key algorithm used for key material parsing.
   *
   * @param keyAlgorithm key algorithm
   */
  public void setKeyAlgorithm(final String keyAlgorithm) {
    this.keyAlgorithm = keyAlgorithm;
  }

  /**
   * Returns trusted peer public keys encoded as Base64 X.509 bytes.
   *
   * @return trusted public keys
   */
  public List<String> getTrustedPublicKeys() {
    return trustedPublicKeys;
  }

  /**
   * Sets trusted peer public keys encoded as Base64 X.509 bytes.
   *
   * @param trustedPublicKeys trusted public keys
   */
  public void setTrustedPublicKeys(final List<String> trustedPublicKeys) {
    this.trustedPublicKeys =
        trustedPublicKeys == null ? new ArrayList<>() : new ArrayList<>(trustedPublicKeys);
  }
}
