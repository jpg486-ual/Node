package es.ual.node.fragmentstorage.adapters.in.web;

import es.ual.node.fragmentstorage.domain.CustodyInventoryItem;
import java.time.Instant;
import java.util.List;

/**
 * Response payload for {@code GET /custody/fragments/by-requester/{nodeId}}. Carries the list of
 * custodied fragments owned by the requester, with derived TTL info so the origin can detect
 * at-risk placements during recovery.
 */
public record CustodyInventoryListPayload(
    String requesterNodeId, int totalFragments, List<CustodyInventoryItemPayload> fragments) {

  /**
   * Builds payload from domain items.
   *
   * @param requesterNodeId origin node identifier
   * @param items inventory items
   * @return payload
   */
  public static CustodyInventoryListPayload of(
      final String requesterNodeId, final List<CustodyInventoryItem> items) {
    return new CustodyInventoryListPayload(
        requesterNodeId,
        items.size(),
        items.stream().map(CustodyInventoryItemPayload::fromDomain).toList());
  }

  /** Per-fragment inventory entry. */
  public record CustodyInventoryItemPayload(
      String fragmentId,
      String agreementId,
      long sizeBytes,
      String checksum,
      Instant expiresAt,
      long ttlRemainingSeconds) {

    static CustodyInventoryItemPayload fromDomain(final CustodyInventoryItem item) {
      return new CustodyInventoryItemPayload(
          item.fragmentId(),
          item.agreementId(),
          item.sizeBytes(),
          item.checksum(),
          item.expiresAt(),
          item.ttlRemainingSeconds());
    }
  }
}
