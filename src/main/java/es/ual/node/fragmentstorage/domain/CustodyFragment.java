package es.ual.node.fragmentstorage.domain;

import java.time.Instant;

/**
 * Immutable metadata of a fragment held under <strong>custody</strong> at a peer node. Created when
 * a sender node POSTs to {@code /custody/fragments}; deleted either by TTL expiry or when the
 * {@link es.ual.node.custodyliveness.adapters.out.recovery.TutorReturnCustodyEscalationPort}
 * transfers the fragment to the requester's tutor as a {@code RecoveryOrphanFragment}
 * (RETURN_TO_TUTOR one-way transition).
 *
 * <p>Distinct type from {@code recovery.domain.RecoveryOrphanFragment}: sharing the field shape but
 * enforcing at compile time that custody-flow code never accidentally persists into the recovery
 * domain and vice versa.
 *
 * @param fragmentId fragment identifier (UUID-style)
 * @param agreementId opaque negotiation/upload agreement identifier
 * @param requesterNodeId node id of the sender: the peer that asked us to custody this fragment
 * @param checksumAlgorithm e.g. {@code SHA-256}
 * @param checksum lowercase hexadecimal digest
 * @param sizeBytes payload size in bytes
 * @param storedAt timestamp when this peer accepted custody
 * @param expiresAt configured retention horizon
 */
public record CustodyFragment(
    String fragmentId,
    String agreementId,
    String requesterNodeId,
    String checksumAlgorithm,
    String checksum,
    int sizeBytes,
    Instant storedAt,
    Instant expiresAt) {}
