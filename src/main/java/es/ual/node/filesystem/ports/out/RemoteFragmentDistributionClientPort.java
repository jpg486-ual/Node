package es.ual.node.filesystem.ports.out;

/**
 * Outbound HTTP port used by the origin to distribute and recover fragments to/from custodian peers
 * via the {@code /custody/fragments} endpoint.
 *
 * <p>Two operations:
 *
 * <ul>
 *   <li>{@link #storeFragment(...)} — signed POST to a custodian, deposits fragment bytes.
 *   <li>{@link #fetchFragment(...)} — signed GET, retrieves fragment bytes for reconstruction.
 * </ul>
 */
public interface RemoteFragmentDistributionClientPort {

  /**
   * Sends a fragment to a remote custodian over a signed HTTP request.
   *
   * @param custodianBaseUrl base URL of the custodian, e.g. {@code http://node2:8080}
   * @param fragmentId fragment identifier (UUID-style)
   * @param agreementId opaque agreement identifier
   * @param payload fragment binary
   * @param checksumAlgorithm e.g. {@code SHA-256}
   * @param checksumHex lowercase hexadecimal digest of the payload
   * @param custodySeconds optional custody retention seconds (null uses custodian default)
   * @throws IllegalStateException when the HTTP exchange fails or returns non-2xx
   */
  void storeFragment(
      String custodianBaseUrl,
      String fragmentId,
      String agreementId,
      byte[] payload,
      String checksumAlgorithm,
      String checksumHex,
      Long custodySeconds);

  /**
   * Retrieves a fragment binary from a remote custodian over a signed HTTP request.
   *
   * @param custodianBaseUrl base URL of the custodian
   * @param fragmentId fragment identifier
   * @return raw bytes
   * @throws IllegalStateException on HTTP failure
   */
  byte[] fetchFragment(String custodianBaseUrl, String fragmentId);
}
