package es.ual.node.filesystem.application;

import es.ual.node.filesystem.domain.FsEntryType;

/**
 * Upsert filesystem entry request. {@code fileId} is mandatory for active {@link FsEntryType#FILE}
 * entries and MUST be {@code null} for directories.
 */
public record FsUpsertRequest(
    String username,
    String entryId,
    String path,
    FsEntryType entryType,
    Long sizeBytes,
    String checksum,
    String fileId,
    Boolean deleted) {}
