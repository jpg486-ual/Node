package es.ual.node.filesystem.adapters.in.web;

import es.ual.node.filesystem.application.FileContentUploadResult;

/** Upload response payload for file content endpoint. */
public record FileContentUploadPayload(String entryId, long sizeBytes, String checksum) {

  /**
   * Maps result to payload.
   *
   * @param result upload result
   * @return payload
   */
  public static FileContentUploadPayload fromResult(final FileContentUploadResult result) {
    return new FileContentUploadPayload(result.entryId(), result.sizeBytes(), result.checksum());
  }
}
