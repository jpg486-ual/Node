package es.ual.node.filesystem.adapters.in.web;

import es.ual.node.filesystem.application.FsUpsertRequest;
import es.ual.node.filesystem.domain.FsEntryType;

/** Upsert filesystem entry payload. */
public record FsUpsertEntryRequestPayload(
    String entryId,
    String path,
    FsEntryType entryType,
    Long sizeBytes,
    String checksum,
    String fileId,
    Boolean deleted) {

  /**
   * Maps payload to use case request.
   *
   * @param username authenticated username
   * @return request model
   */
  public FsUpsertRequest toApplication(final String username) {
    return new FsUpsertRequest(
        username, entryId, path, entryType, sizeBytes, checksum, fileId, deleted);
  }
}
