package es.ual.node.reedsolomon.ports.out;

/** Outbound port for validating reconstructed payload integrity. */
public interface RsIntegrityVerifierPort {

  /**
   * Validates payload bytes against an expected hash.
   *
   * @param bytes payload bytes
   * @param expectedHash expected content hash
   * @return true when integrity validation succeeds
   */
  boolean verify(byte[] bytes, String expectedHash);
}
