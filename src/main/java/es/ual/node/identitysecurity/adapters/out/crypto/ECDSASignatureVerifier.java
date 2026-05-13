package es.ual.node.identitysecurity.adapters.out.crypto;

import es.ual.node.identitysecurity.ports.out.SignatureVerifier;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** ECDSA implementation of {@link SignatureVerifier}. */
public class ECDSASignatureVerifier implements SignatureVerifier {

  private static final Logger LOGGER = LoggerFactory.getLogger(ECDSASignatureVerifier.class);

  /** {@inheritDoc} */
  @Override
  public boolean verify(
      final String algorithm,
      final byte[] payload,
      final byte[] signature,
      final PublicKey publicKey) {
    if (algorithm == null || algorithm.isBlank()) {
      return false;
    }
    if (payload == null || signature == null || publicKey == null) {
      return false;
    }

    try {
      Signature verifier = Signature.getInstance(algorithm);
      verifier.initVerify(publicKey);
      verifier.update(payload);
      return verifier.verify(signature);
    } catch (GeneralSecurityException ex) {
      LOGGER
          .atWarn()
          .setMessage("Signature verification failed due to crypto error")
          .addKeyValue("algorithm", algorithm)
          .addKeyValue("error", ex.getClass().getSimpleName())
          .log();
      return false;
    }
  }
}
