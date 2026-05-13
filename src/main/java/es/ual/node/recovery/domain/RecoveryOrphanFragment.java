package es.ual.node.recovery.domain;

import java.time.Instant;

/**
 * Immutable metadata of an <strong>orphan</strong> fragment held by a tutor on behalf of a node
 * that has been detected unresponsive by custody-liveness probes. Created when the tutor receives a
 * POST to {@code /recovery/fragments} from a peer that is escalating custody under the {@code
 * RETURN_TO_TUTOR} policy.
 *
 * <ol>
 *   <li>El nodo emisor recovered los reclama vía {@code POST
 *       /recovery/orphan-fragments/{fragmentId}/claim} (descarga bytes) + {@code POST
 *       /recovery/orphan-fragments/{fragmentId}/ack} (confirma descarga, tutor borra).
 * </ol>
 *
 * <p>El recovery flow opera sobre su tabla física {@code recovery_orphan_fragment} con sus propios
 * ports/adapters, físicamente disjunta de la tabla {@code custody_fragment} del custody flow. La
 * transición de custody a orphan es HTTP-mediada (read custody, POST tutor's {@code
 * /recovery/fragments}, delete custody) y en un solo sentido.
 *
 * @param fragmentId fragment identifier (UUID-style)
 * @param agreementId opaque negotiation/upload agreement identifier (from the original custody-flow
 *     agreement)
 * @param requesterNodeId node id of the failed requester, the node whose admin will eventually
 *     reconstruct it
 * @param checksumAlgorithm e.g. {@code SHA-256}
 * @param checksum lowercase hexadecimal digest
 * @param sizeBytes payload size in bytes
 * @param storedAt timestamp when the tutor received the orphan
 */
public record RecoveryOrphanFragment(
    String fragmentId,
    String agreementId,
    String requesterNodeId,
    String checksumAlgorithm,
    String checksum,
    int sizeBytes,
    Instant storedAt) {}
