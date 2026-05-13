package es.ual.node.fragmentstorage.domain;

import java.time.Instant;

/**
 * Snapshot of a custodied fragment in this node's inventory, sized for the {@code GET
 * /custody/fragments/by-requester/{nodeId}} endpoint. Used by an origin in recovery to learn which
 * of its fragments are still alive at each peer + their remaining custody window.
 *
 * @param fragmentId fragment identifier
 * @param agreementId backing agreement identifier
 * @param sizeBytes payload size in bytes
 * @param checksum lowercase hex SHA-256
 * @param expiresAt configured custody expiry
 * @param ttlRemainingSeconds derived value: max(0, {@code expiresAt - now}). Computed at query
 *     time; not persisted. Origin uses it to detect at-risk fragments.
 */
public record CustodyInventoryItem(
    String fragmentId,
    String agreementId,
    long sizeBytes,
    String checksum,
    Instant expiresAt,
    long ttlRemainingSeconds) {}
