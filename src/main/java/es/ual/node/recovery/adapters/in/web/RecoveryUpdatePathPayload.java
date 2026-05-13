package es.ual.node.recovery.adapters.in.web;

/**
 * Inbound DTO for {@code PATCH /recovery/file-manifests/{fileId}}.
 *
 * <p>Carries the metadata-only update of a custodied manifest after a {@code FsEntry} rename or
 * move at the origin.
 *
 * @param directoryPath new path (must satisfy the same regex as the original at upload)
 * @param originalFileName new file name (no path separators)
 */
public record RecoveryUpdatePathPayload(String directoryPath, String originalFileName) {}
