package es.ual.node.recovery.adapters.in.web;

import java.util.List;

/**
 * Inbound DTO for {@code PATCH /recovery/file-manifests/_bulk}.
 *
 * <p>Carries N metadata-only updates of custodied manifests in one signed roundtrip. Used by the
 * origin's sub-tree MOVE flow ({@code POST /fs/entries/move-subtree}) to keep the tutor's view
 * coherent with a single network exchange instead of N. The tutor processes the array as a single
 * JPA transaction: atomicidad estricta: o todo o nada (RFC 7807 / "Transactional outbox" pattern,
 * Hohpe & Woolf 2003).
 *
 * @param entries non-empty list of single-entry updates; each one shares the same shape as {@link
 *     RecoveryUpdatePathPayload}
 */
public record RecoveryBulkUpdatePathPayload(List<Entry> entries) {

  /**
   * Single update inside the bulk payload.
   *
   * @param fileId manifest identifier (must belong to the caller's {@code requesterNodeId})
   * @param directoryPath new path
   * @param originalFileName new file name (no path separators)
   */
  public record Entry(String fileId, String directoryPath, String originalFileName) {}
}
