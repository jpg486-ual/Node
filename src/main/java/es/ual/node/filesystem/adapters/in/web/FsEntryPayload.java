package es.ual.node.filesystem.adapters.in.web;

import es.ual.node.filesystem.domain.FsEntry;
import es.ual.node.filesystem.domain.FsEntryType;
import java.time.Instant;

/** Filesystem entry response payload. */
public record FsEntryPayload(
    String entryId,
    String path,
    FsEntryType entryType,
    long sizeBytes,
    String checksum,
    long version,
    Instant updatedAt,
    boolean deleted) {

  /**
   * Maps domain entry to payload.
   *
   * @param entry domain entry
   * @return payload
   */
  public static FsEntryPayload fromDomain(final FsEntry entry) {
    return new FsEntryPayload(
        entry.entryId(),
        entry.path(),
        entry.entryType(),
        entry.sizeBytes(),
        entry.checksum(),
        entry.version(),
        entry.updatedAt(),
        entry.deleted());
  }
}
