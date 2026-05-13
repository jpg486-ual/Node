package es.ual.node.fragmentstorage.adapters.in.web;

import es.ual.node.fragmentstorage.domain.CustodyFragment;
import java.time.Instant;

/** Response payload returned after a successful POST /custody/fragments. */
public record CustodyStoredFragmentPayload(
    String fragmentId,
    String agreementId,
    String senderNodeId,
    String checksumAlgorithm,
    String checksum,
    int sizeBytes,
    Instant storedAt,
    Instant expiresAt) {

  public static CustodyStoredFragmentPayload fromDomain(final CustodyFragment stored) {
    return new CustodyStoredFragmentPayload(
        stored.fragmentId(),
        stored.agreementId(),
        stored.requesterNodeId(),
        stored.checksumAlgorithm(),
        stored.checksum(),
        stored.sizeBytes(),
        stored.storedAt(),
        stored.expiresAt());
  }
}
