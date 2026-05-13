package es.ual.node.identitysecurity.adapters.out.crypto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests para {@link ECDSASignatureVerifier}.
 *
 * <p>Usa JCA real ({@link Signature#getInstance(String)} con {@code SHA256withECDSA} y curva P-256)
 * no doblamos {@link Signature}. Verifica la verdad matemática del wrapper: firma válida → true,
 * tampering en payload o firma → false, clave pública distinta → false, algoritmo desconocido →
 * false (graceful degradation).
 */
class ECDSASignatureVerifierTest {

  private static final String ALGORITHM = "SHA256withECDSA";
  private static final byte[] PAYLOAD =
      "GET\n/ops/health\n\nnonce-1\n1700000000".getBytes(StandardCharsets.UTF_8);

  private ECDSASignatureVerifier verifier;
  private KeyPair keyPair;

  @BeforeEach
  void setUp() throws GeneralSecurityException {
    verifier = new ECDSASignatureVerifier();
    keyPair = generateEcKeyPair();
  }

  @Test
  void verify_returnsTrueWithValidSignature() throws GeneralSecurityException {
    byte[] signature = sign(PAYLOAD, keyPair.getPrivate());

    boolean result = verifier.verify(ALGORITHM, PAYLOAD, signature, keyPair.getPublic());

    assertTrue(result, "freshly produced signature must verify");
  }

  @Test
  void verify_returnsFalseWithTamperedPayload() throws GeneralSecurityException {
    byte[] signature = sign(PAYLOAD, keyPair.getPrivate());
    byte[] tampered = PAYLOAD.clone();
    tampered[0] ^= 0x01;

    boolean result = verifier.verify(ALGORITHM, tampered, signature, keyPair.getPublic());

    assertFalse(result, "single-byte payload tamper must invalidate signature");
  }

  @Test
  void verify_returnsFalseWithTamperedSignature() throws GeneralSecurityException {
    byte[] signature = sign(PAYLOAD, keyPair.getPrivate());
    signature[signature.length - 1] ^= 0x01;

    boolean result = verifier.verify(ALGORITHM, PAYLOAD, signature, keyPair.getPublic());

    assertFalse(result, "single-byte signature tamper must fail verification");
  }

  @Test
  void verify_returnsFalseWithDifferentPublicKey() throws GeneralSecurityException {
    byte[] signature = sign(PAYLOAD, keyPair.getPrivate());
    KeyPair otherPair = generateEcKeyPair();

    boolean result = verifier.verify(ALGORITHM, PAYLOAD, signature, otherPair.getPublic());

    assertFalse(result, "verification with foreign public key must fail");
  }

  @Test
  void verify_returnsFalseWhenAlgorithmUnsupported() throws GeneralSecurityException {
    byte[] signature = sign(PAYLOAD, keyPair.getPrivate());

    boolean result = verifier.verify("UNKNOWN-ALG", PAYLOAD, signature, keyPair.getPublic());

    assertFalse(result, "NoSuchAlgorithmException must be translated to false");
  }

  @Test
  void verify_returnsFalseOnNullOrBlankInputs() throws GeneralSecurityException {
    byte[] signature = sign(PAYLOAD, keyPair.getPrivate());

    assertFalse(verifier.verify(null, PAYLOAD, signature, keyPair.getPublic()));
    assertFalse(verifier.verify("  ", PAYLOAD, signature, keyPair.getPublic()));
    assertFalse(verifier.verify(ALGORITHM, null, signature, keyPair.getPublic()));
    assertFalse(verifier.verify(ALGORITHM, PAYLOAD, null, keyPair.getPublic()));
    assertFalse(verifier.verify(ALGORITHM, PAYLOAD, signature, null));
  }

  private static byte[] sign(final byte[] payload, final PrivateKey privateKey)
      throws GeneralSecurityException {
    Signature signer = Signature.getInstance(ALGORITHM);
    signer.initSign(privateKey);
    signer.update(payload);
    return signer.sign();
  }

  private static KeyPair generateEcKeyPair() throws GeneralSecurityException {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    return generator.generateKeyPair();
  }
}
