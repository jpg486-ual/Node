package es.ual.node.recovery.adapters.in.web;

import java.util.List;

/**
 * Inbound DTO for {@code DELETE /recovery/file-manifests-bulk}.
 *
 * <p>Carries N {@code fileId}s to remove from tutor custody in a single signed roundtrip. Used by
 * the origin's sub-tree DELETE flow ({@code POST /fs/entries/delete-subtree}) to keep the tutor
 * free of orphaned manifests with a single network exchange instead of N. Idempotent: missing
 * fileIds are reported in {@code missingCount} but do not abort the operation, mirroring the
 * single-DELETE semantics that treat 404 as success.
 *
 * @param fileIds non-empty list of manifest identifiers; all must belong to the caller's {@code
 *     requesterNodeId} (cross-owner deletion is rejected with 403)
 */
public record RecoveryBulkDeletePayload(List<String> fileIds) {}
